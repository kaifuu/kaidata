package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.develop.DevScriptExecutor;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据模型 [SYS_ADMIN]：模型 + 模型表 + 模型字段（字段可关联数据元，落地数据标准）。 */
@RestController
@RequestMapping("/api/data-gov/model")
@CrossOrigin(origins = "*")
public class DataModelController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor scriptExecutor;
    @Autowired private com.pharma.service.access.adapter.DataSourceLoader loader;
    @Autowired private com.pharma.service.access.ingest.IcebergWriter icebergWriter;
    @Autowired private com.pharma.service.access.layer.LayerRouter layerRouter;
    private final ObjectMapper json = new ObjectMapper();

    // ===== 模型 =====
    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String domain) {
        Authz.require(Authz.SYS_ADMIN);
        // 带表数/字段数统计，列表页直观看到每个模型建到什么程度
        String base = "SELECT m.id, m.name, m.domain, m.model_type, m.description, m.status, m.create_time, " +
                "(SELECT COUNT(*) FROM meta.gov_model_table t WHERE t.model_id=m.id) AS table_count, " +
                "(SELECT COUNT(*) FROM meta.gov_model_field f JOIN meta.gov_model_table t2 ON f.table_id=t2.id WHERE t2.model_id=m.id) AS field_count " +
                "FROM meta.gov_model m";
        if (domain == null || domain.isEmpty()) return jdbc.queryForList(base + " ORDER BY m.id");
        return jdbc.queryForList(base + " WHERE m.domain=? ORDER BY m.id", domain);
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_model(id, name, domain, model_type, description, status, create_time) VALUES (?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("domain")), str(b.getOrDefault("model_type", "逻辑模型")), str(b.get("description")), str(b.getOrDefault("status", "NORMAL")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_model SET name=?, domain=?, model_type=?, description=?, status=? WHERE id=?",
                str(b.get("name")), str(b.get("domain")), str(b.get("model_type")), str(b.get("description")), str(b.getOrDefault("status", "NORMAL")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Long> tids = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT id FROM meta.gov_model_table WHERE model_id=?", id)) tids.add(lng(r.get("id")));
        for (Long tid : tids) jdbc.update("DELETE FROM meta.gov_model_field WHERE table_id=?", tid);
        jdbc.update("DELETE FROM meta.gov_model_relation WHERE model_id=?", id);
        jdbc.update("DELETE FROM meta.gov_model_table WHERE model_id=?", id);
        jdbc.update("DELETE FROM meta.gov_model WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 模型表 =====
    @GetMapping("/table")
    public List<Map<String, Object>> listTable(@RequestParam long modelId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, model_id, name, layer, description FROM meta.gov_model_table WHERE model_id=? ORDER BY id", modelId);
    }
    @PostMapping("/table")
    public Map<String, Object> createTable(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_model_table(id, model_id, name, layer, description) VALUES (?,?,?,?,?)",
                id, lng(b.get("model_id")), str(b.get("name")), str(b.get("layer")), str(b.get("description")));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/table")
    public Map<String, Object> updateTable(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_model_table SET name=?, layer=?, description=? WHERE id=?",
                str(b.get("name")), str(b.get("layer")), str(b.get("description")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping("/table")
    public Map<String, Object> deleteTable(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_model_field WHERE table_id=?", id);
        jdbc.update("DELETE FROM meta.gov_model_relation WHERE table_a=? OR table_b=?", id, id);
        jdbc.update("DELETE FROM meta.gov_model_table WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 模型字段 =====
    @GetMapping("/field")
    public List<Map<String, Object>> listField(@RequestParam long tableId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList(
                "SELECT f.id, f.table_id, f.name, f.data_type, f.element_id, f.is_pk, f.nullable, f.comment, " +
                "e.name AS element_name, e.code AS element_code " +
                "FROM meta.gov_model_field f " +
                "LEFT JOIN meta.gov_data_element e ON e.id = f.element_id " +
                "WHERE f.table_id=? ORDER BY f.id", tableId);
    }
    @PostMapping("/field")
    public Map<String, Object> createField(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        long elementId = lng(b.get("element_id"));
        String dataType = resolveType(elementId, str(b.get("data_type")));
        jdbc.update("INSERT INTO meta.gov_model_field(id, table_id, name, data_type, element_id, is_pk, nullable, comment) VALUES (?,?,?,?,?,?,?,?)",
                id, lng(b.get("table_id")), str(b.get("name")), dataType, elementId,
                bool(b.get("is_pk")), bool(b.get("nullable")), str(b.get("comment")));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/field")
    public Map<String, Object> updateField(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long elementId = lng(b.get("element_id"));
        String dataType = resolveType(elementId, str(b.get("data_type")));
        jdbc.update("UPDATE meta.gov_model_field SET name=?, data_type=?, element_id=?, is_pk=?, nullable=?, comment=? WHERE id=?",
                str(b.get("name")), dataType, elementId, bool(b.get("is_pk")), bool(b.get("nullable")), str(b.get("comment")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping("/field")
    public Map<String, Object> deleteField(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_model_field WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 表间关系（ER 图数据源） =====

    @GetMapping("/relation")
    public List<Map<String, Object>> listRelation(@RequestParam long modelId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList(
                "SELECT r.id, r.model_id, r.table_a, r.field_a, r.table_b, r.field_b, r.relation_type, " +
                        "ta.name AS table_a_name, tb.name AS table_b_name, r.create_time " +
                        "FROM meta.gov_model_relation r " +
                        "LEFT JOIN meta.gov_model_table ta ON ta.id=r.table_a " +
                        "LEFT JOIN meta.gov_model_table tb ON tb.id=r.table_b " +
                        "WHERE r.model_id=? ORDER BY r.id", modelId);
    }
    @PostMapping("/relation")
    public Map<String, Object> createRelation(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_model_relation(id, model_id, table_a, field_a, table_b, field_b, relation_type, create_time) VALUES (?,?,?,?,?,?,?,?)",
                System.currentTimeMillis(), lng(b.get("model_id")), lng(b.get("table_a")), str(b.get("field_a")),
                lng(b.get("table_b")), str(b.get("field_b")), strOrDefault(b.get("relation_type"), "1:N"), new Timestamp(System.currentTimeMillis()));
        return Map.of("success", true);
    }
    @DeleteMapping("/relation")
    public Map<String, Object> deleteRelation(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_model_relation WHERE id=?", id);
        return Map.of("success", true);
    }

    /** ER 图数据：模型下全部表（含字段/pk/数据元绑定）+ 关系边，前端画图用。 */
    @GetMapping("/er")
    public Map<String, Object> er(@RequestParam long modelId) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT t.id, t.name, t.layer, t.description FROM meta.gov_model_table t WHERE t.model_id=? ORDER BY t.id", modelId);
        for (Map<String, Object> t : tables) {
            t.put("fields", jdbc.queryForList(
                    "SELECT f.id, f.name, f.data_type, f.is_pk, f.comment, e.name AS element_name " +
                            "FROM meta.gov_model_field f LEFT JOIN meta.gov_data_element e ON e.id=f.element_id " +
                            "WHERE f.table_id=? ORDER BY f.id", lng(t.get("id"))));
        }
        List<Map<String, Object>> relations = jdbc.queryForList(
                "SELECT r.id, r.table_a, r.field_a, r.table_b, r.field_b, r.relation_type FROM meta.gov_model_relation r WHERE r.model_id=? ORDER BY r.id", modelId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        out.put("relations", relations);
        return out;
    }

    // ===== 模型版本快照（对比/回溯） =====

    /** 存版本：模型当前全部表+字段+关系打成 JSON 快照，version_n 递增。 */
    @PostMapping("/version")
    public Map<String, Object> saveVersion(@RequestParam long modelId, @RequestParam(required = false, defaultValue = "") String detail) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> er = er(modelId);
        Integer maxN = jdbc.queryForObject("SELECT MAX(version_n) FROM meta.gov_model_version WHERE model_id=?", Integer.class, modelId);
        int next = (maxN == null ? 0 : maxN) + 1;
        String snapshot;
        try { snapshot = json.writeValueAsString(er); } catch (Exception e) { throw new IllegalArgumentException("快照序列化失败: " + e.getMessage()); }
        jdbc.update("INSERT INTO meta.gov_model_version(id, model_id, version_n, snapshot_json, change_detail, create_time) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), modelId, next, snapshot, detail.isEmpty() ? ("v" + next + " 快照") : detail, new Timestamp(System.currentTimeMillis()));
        return Map.of("success", true, "version", next);
    }

    @GetMapping("/version/list")
    public List<Map<String, Object>> versionList(@RequestParam long modelId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, model_id, version_n, change_detail, create_time FROM meta.gov_model_version WHERE model_id=? ORDER BY version_n DESC", modelId);
    }

    /** 版本对比：表/字段级 diff（新增/删除/类型变化/落标变化）。 */
    @GetMapping("/version/compare")
    public Map<String, Object> versionCompare(@RequestParam long modelId, @RequestParam int v1, @RequestParam int v2) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", v1);
        out.put("v2", v2);
        out.put("diff", diffSnapshots(snapshotOf(modelId, v1), snapshotOf(modelId, v2)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotOf(long modelId, int versionN) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT snapshot_json FROM meta.gov_model_version WHERE model_id=? AND version_n=? ORDER BY id DESC LIMIT 1", modelId, versionN);
            return rows.isEmpty() ? Map.of() : json.readValue(str(rows.get(0).get("snapshot_json")), Map.class);
        } catch (Exception e) { return Map.of(); }
    }

    /** 两份 ER 快照 diff：按 表.字段 对比类型与数据元绑定。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> diffSnapshots(Map<String, Object> s1, Map<String, Object> s2) {
        Map<String, String> flat1 = flattenSnapshot(s1);
        Map<String, String> flat2 = flattenSnapshot(s2);
        List<Map<String, Object>> diff = new ArrayList<>();
        for (String k : flat1.keySet()) {
            if (!flat2.containsKey(k)) diff.add(Map.of("type", "REMOVED", "target", k, "old", flat1.get(k), "new", ""));
            else if (!flat1.get(k).equals(flat2.get(k))) diff.add(Map.of("type", "CHANGED", "target", k, "old", flat1.get(k), "new", flat2.get(k)));
        }
        for (String k : flat2.keySet()) {
            if (!flat1.containsKey(k)) diff.add(Map.of("type", "ADDED", "target", k, "old", "", "new", flat2.get(k)));
        }
        return diff;
    }

    /** 快照压平：表 → "表:表名"，字段 → "表名.字段名"=类型[|数据元]。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> flattenSnapshot(Map<String, Object> snapshot) {
        Map<String, String> out = new LinkedHashMap<>();
        if (snapshot == null) return out;
        Object tablesObj = snapshot.get("tables");
        if (!(tablesObj instanceof List)) return out;
        for (Object tObj : (List<Object>) tablesObj) {
            Map<String, Object> t = (Map<String, Object>) tObj;
            String tName = str(t.get("name"));
            out.put("表:" + tName, "layer=" + str(t.get("layer")));
            Object fieldsObj = t.get("fields");
            if (!(fieldsObj instanceof List)) continue;
            for (Object fObj : (List<Object>) fieldsObj) {
                Map<String, Object> f = (Map<String, Object>) fObj;
                String val = str(f.get("data_type")) + (bool(f.get("is_pk")) ? "|PK" : "");
                String el = str(f.get("element_name"));
                if (!el.isEmpty()) val += "|" + el;
                out.put(tName + "." + str(f.get("name")), val);
            }
        }
        return out;
    }

    // ===== 模型落地：DDL 生成 / 一键建物理表 / 物理表逆向导入 =====

    /** 生成模型表的建表 DDL。默认 StarRocks（复用 StarRocksDdlBuilder，db=model_table.layer）；
     *  传 dsId 且目标为 iceberg 数据源时生成 Iceberg 湖表 DDL（namespace=model_table.layer，三段名展示）。 */
    @GetMapping("/table/ddl")
    public Map<String, Object> generateDdl(@RequestParam long tableId,
                                           @RequestParam(required = false) Long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> t = jdbc.queryForMap("SELECT name, layer FROM meta.gov_model_table WHERE id=?", tableId);
        String layer = str(t.get("layer"));
        if (dsId != null && lakeDsType(dsId).equals("iceberg")) {
            String ns = layer.isEmpty() ? "demo" : layer;
            return Map.of("ddl", buildIcebergDdl(tableId, ns), "db",
                    com.pharma.service.access.adapter.IcebergAdapter.SR_CATALOG + "." + ns, "table", str(t.get("name")));
        }
        return Map.of("ddl", buildDdl(tableId), "db", layer, "table", str(t.get("name")));
    }

    /** 一键建物理表：显式选数据源时按原路径（StarRocks DDL→DevScriptExecutor / iceberg 湖表）；
     *  不选（dsId=0）按「层→绑定数据源」自动路由（LayerRouter：绑定湖→湖表、绑定内部库→方言建表、
     *  无绑定→主库，主库保留 PRIMARY KEY 模型）。建表前按层 naming_pattern 前置校验。
     *  成功后登记元数据（模型→物理→数据地图闭环）。 */
    @PostMapping("/table/create-physical")
    public Map<String, Object> createPhysical(@RequestParam long tableId,
                                              @RequestParam(required = false, defaultValue = "0") long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> t = jdbc.queryForMap("SELECT name, layer FROM meta.gov_model_table WHERE id=?", tableId);
        String layer = str(t.get("layer"));
        if (layer.isEmpty()) layer = "ods";
        String name = str(t.get("name"));
        // 命名规范前置校验（违规拦截并给出建议名）
        String bad = layerRouter.validateNaming(layer, name);
        if (bad != null) return Map.of("success", false, "msg", bad);

        boolean ok;
        String ddl = "";
        String target;
        String errMsg = "";
        String dsType = dsId > 0 ? lakeDsType(dsId) : (layerRouter.resolve(layer).kind().equals("ICEBERG") ? "iceberg" : "");
        if (dsType.equals("iceberg")) {
            long realDs = dsId > 0 ? dsId : layerRouter.resolve(layer).dsId();
            ddl = buildIcebergDdl(tableId, layer);
            boolean created = icebergWriter.createTable(loader.load(realDs), layer, name, modelCols(tableId));
            ok = created;
            target = com.pharma.service.access.adapter.IcebergAdapter.SR_CATALOG + "." + layer + "（湖表）";
            if (!created) errMsg = "湖表已存在：" + target + "." + name;
        } else if (dsId <= 0) {
            // 按层绑定自动路由（无绑定=主库，主库保留 pk 主键模型）
            try {
                String where = layerRouter.createIfAbsent(layer, name, modelDdlCols(tableId), pkOf(tableId));
                ddl = buildDdl(tableId);
                ok = where != null;
                target = where == null ? layer : where;
                if (!ok) errMsg = "目标已存在同名表，未重复创建";
            } catch (IllegalArgumentException ne) {
                return Map.of("success", false, "msg", ne.getMessage());
            } catch (Exception e) {
                return Map.of("success", false, "msg", "建表失败：" + rootMsgOf(e), "ddl", buildDdl(tableId));
            }
        } else {
            ddl = buildDdl(tableId);
            Map<String, Object> r = scriptExecutor.executeSql(dsId, ddl);
            ok = "SUCCESS".equals(str(r.get("status")));
            errMsg = str(r.get("msg"));
            target = "ds#" + dsId;
        }
        boolean registered = false;
        if (ok) registered = registerMeta(tableId, dsId > 0 ? dsId : 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("msg", ok ? (registered ? "建表成功 → " + target + "，已同步登记元数据" : "建表成功 → " + target + "（元数据登记失败，可到采集管理手动同步）") : errMsg);
        out.put("ddl", ddl);
        out.put("target", target);
        return out;
    }

    /** 模型字段 → 建表列定义（类型空则 STRING）。 */
    private List<StarRocksDdlBuilder.ColumnDef> modelDdlCols(long tableId) {
        List<StarRocksDdlBuilder.ColumnDef> cols = new ArrayList<>();
        for (Map<String, Object> f : jdbc.queryForList(
                "SELECT name, data_type FROM meta.gov_model_field WHERE table_id=? ORDER BY id", tableId)) {
            String ty = str(f.get("data_type"));
            cols.add(new StarRocksDdlBuilder.ColumnDef(str(f.get("name")), ty.isEmpty() ? "STRING" : ty));
        }
        return cols;
    }

    /** 模型表首个主键字段名（无则空串）。 */
    private String pkOf(long tableId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM meta.gov_model_field WHERE table_id=? AND is_pk=1 ORDER BY id LIMIT 1", tableId);
        return rows.isEmpty() ? "" : str(rows.get(0).get("name"));
    }

    private static String rootMsgOf(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }

    /** 目标数据源类型（ing_datasource.type；不存在返回空串走原 StarRocks 路径）。 */
    private String lakeDsType(long dsId) {
        try { return str(jdbc.queryForObject("SELECT type FROM meta.ing_datasource WHERE id=?", String.class, dsId)); }
        catch (Exception e) { return ""; }
    }

    /** 模型字段 → 湖表列定义 [{name, type, comment}]。 */
    private List<Map<String, Object>> modelCols(long tableId) {
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Map<String, Object> f : jdbc.queryForList(
                "SELECT name, data_type, comment FROM meta.gov_model_field WHERE table_id=? ORDER BY id", tableId)) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", str(f.get("name")));
            c.put("type", str(f.get("data_type")).isEmpty() ? "STRING" : str(f.get("data_type")));
            c.put("comment", str(f.get("comment")));
            cols.add(c);
        }
        return cols;
    }

    /** Iceberg 湖表 DDL 文本（展示/复制用；物理建表走 REST，不在 SR 执行）。 */
    private String buildIcebergDdl(long tableId, String ns) {
        Map<String, Object> t = jdbc.queryForMap("SELECT name FROM meta.gov_model_table WHERE id=?", tableId);
        StringBuilder sb = new StringBuilder("CREATE TABLE ")
                .append(com.pharma.service.access.adapter.IcebergAdapter.SR_CATALOG)
                .append(".").append(ns).append(".").append(str(t.get("name"))).append(" (\n");
        List<Map<String, Object>> cols = modelCols(tableId);
        for (int i = 0; i < cols.size(); i++) {
            Map<String, Object> c = cols.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("  ").append(c.get("name")).append(" ").append(String.valueOf(c.get("type")).toLowerCase());
            String cm = str(c.get("comment"));
            if (!cm.isEmpty()) sb.append(" COMMENT '").append(cm.replace("'", "''")).append("'");
        }
        return sb.append("\n) USING iceberg;").toString();
    }

    /** 建成物理表后登记/刷新 gov_meta_table（存在则 UPDATE 列结构与同步时间，避免删行破坏资产挂载引用）。 */
    private boolean registerMeta(long tableId, long dsId) {
        try {
            Map<String, Object> t = jdbc.queryForMap("SELECT name, layer, description FROM meta.gov_model_table WHERE id=?", tableId);
            String db = str(t.get("layer")); if (db.isEmpty()) db = "ods";
            // 湖表登记为 SR 三段名前缀（iceberg_catalog.<ns=layer>），数据地图/质量/资产按此直查主库
            if (lakeDsType(dsId).equals("iceberg")) {
                if (str(t.get("layer")).isEmpty()) db = com.pharma.service.access.adapter.IcebergAdapter.defaultNs();
                db = com.pharma.service.access.adapter.IcebergAdapter.SR_CATALOG + "." + db;
            }
            String table = str(t.get("name"));
            List<Map<String, Object>> fs = jdbc.queryForList(
                    "SELECT name, data_type, is_pk, comment FROM meta.gov_model_field WHERE table_id=? ORDER BY id", tableId);
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Map<String, Object> f : fs) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", str(f.get("name")));
                c.put("type", str(f.get("data_type")).isEmpty() ? "STRING" : str(f.get("data_type")));
                c.put("comment", str(f.get("comment")));
                if (bool(f.get("is_pk"))) c.put("key", "PRI");
                cols.add(c);
            }
            String colsJson = json.writeValueAsString(cols);
            Integer exist = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM meta.gov_meta_table WHERE ds_id=? AND schema_name=? AND table_name=?",
                    Integer.class, dsId, db, table);
            if (exist != null && exist > 0) {
                jdbc.update("UPDATE meta.gov_meta_table SET columns_json=?, comment=?, synced_time=? WHERE ds_id=? AND schema_name=? AND table_name=?",
                        colsJson, str(t.get("description")), new Timestamp(System.currentTimeMillis()), dsId, db, table);
            } else {
                jdbc.update("INSERT INTO meta.gov_meta_table(id, ds_id, schema_name, table_name, comment, columns_json, row_count, synced_time) VALUES (?,?,?,?,?,?,0,?)",
                        System.currentTimeMillis(), dsId, db, table, str(t.get("description")), colsJson, new Timestamp(System.currentTimeMillis()));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 物理表逆向导入模型（增强）：从 gov_meta_table.columns_json 批量生成模型表+字段。
     * <ul>
     *   <li>支持一次多选：metaIds 传逗号分隔的元数据表 id</li>
     *   <li>同名表已存在于当前模型 → 跳过并回报（不重复导入）</li>
     *   <li>PK：优先读列元数据 key 标记（PRI/key=true），无标记再按 id/*_id 推断</li>
     *   <li>数据元自动匹配：列名（含注释）↔数据元相似度（复用 Std 的 similarity），≥80 自动挂标准</li>
     * </ul>
     */
    @PostMapping("/reverse")
    public Map<String, Object> reverse(@RequestParam String metaIds, @RequestParam long modelId,
                                       @RequestParam(required = false, defaultValue = "ods") String layer) {
        Authz.require(Authz.SYS_ADMIN);
        if (metaIds == null || metaIds.isEmpty()) throw new IllegalArgumentException("请选择要导入的物理表");
        // 数据元池（相似度匹配用）
        List<Map<String, Object>> elements = jdbc.queryForList(
                "SELECT id, name, en_name FROM meta.gov_data_element WHERE status='NORMAL'");
        int imported = 0, fields = 0, matched = 0;
        List<String> skipped = new ArrayList<>();
        for (String seg : metaIds.split(",")) {
            long metaId;
            try { metaId = Long.parseLong(seg.trim()); } catch (Exception e) { continue; }
            Map<String, Object> meta;
            try { meta = jdbc.queryForMap("SELECT table_name, columns_json FROM meta.gov_meta_table WHERE id=?", metaId); }
            catch (Exception e) { skipped.add(seg + "(元数据不存在)"); continue; }
            String tName = str(meta.get("table_name"));
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM meta.gov_model_table WHERE model_id=? AND name=?", Integer.class, modelId, tName);
            if (dup != null && dup > 0) { skipped.add(tName); continue; }
            long tableId = System.currentTimeMillis() + imported;
            jdbc.update("INSERT INTO meta.gov_model_table(id, model_id, name, layer, description) VALUES (?,?,?,?,?)",
                    tableId, modelId, tName, str(layer), "逆向导入自 " + tName);
            imported++;
            try {
                List<?> cols = json.readValue(str(meta.get("columns_json")), List.class);
                int n = 0;
                for (Object o : cols) {
                    String nm, ty, cm, key;
                    if (o instanceof Map) {
                        Map<?, ?> c = (Map<?, ?>) o;
                        nm = str(c.get("name")); ty = str(c.get("type")); cm = str(c.get("comment"));
                        key = str(c.get("key")) + str(c.get("pri"));
                    } else { nm = str(o); ty = ""; cm = ""; key = ""; }
                    if (nm.isEmpty()) continue;
                    if (ty.isEmpty()) ty = "STRING";
                    boolean pk = key.contains("PRI") || key.equalsIgnoreCase("true") || nm.equals("id") || (nm.endsWith("_id") && !nm.endsWith("uuid"));
                    long elementId = 0;
                    for (Map<String, Object> e : elements) {
                        // 列名、注释分别与数据元算相似度取最大（拼接会让 contains 匹配失配）
                        int s = DataStdController.similarity(nm, str(e.get("name")), str(e.get("en_name")));
                        if (!cm.isEmpty()) s = Math.max(s, DataStdController.similarity(cm, str(e.get("name")), str(e.get("en_name"))));
                        if (s >= 80) { elementId = lng(e.get("id")); matched++; break; }
                    }
                    jdbc.update("INSERT INTO meta.gov_model_field(id, table_id, name, data_type, element_id, is_pk, nullable, comment) VALUES (?,?,?,?,?,?,?,?)",
                            System.currentTimeMillis() + imported * 1000L + (n++), tableId, nm, ty, elementId, pk, !pk, cm);
                }
                fields += n;
            } catch (Exception e) {
                return Map.of("success", false, "msg", "解析 " + tName + " 的 columns_json 失败: " + e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("imported", imported);
        out.put("fields", fields);
        out.put("stdMatched", matched);
        out.put("skipped", String.join("、", skipped));
        return out;
    }

    /** 把模型表+字段拼成 StarRocks DDL（有 pk→PRIMARY KEY，无→DUPLICATE KEY；db=layer，空则 ods）。 */
    private String buildDdl(long tableId) {
        Map<String, Object> t = jdbc.queryForMap("SELECT name, layer FROM meta.gov_model_table WHERE id=?", tableId);
        String db = str(t.get("layer"));
        if (db.isEmpty()) db = "ods";
        List<StarRocksDdlBuilder.ColumnDef> cols = new ArrayList<>();
        String pk = "";
        for (Map<String, Object> f : jdbc.queryForList("SELECT name, data_type, is_pk FROM meta.gov_model_field WHERE table_id=? ORDER BY id", tableId)) {
            String nm = str(f.get("name")), ty = str(f.get("data_type"));
            cols.add(new StarRocksDdlBuilder.ColumnDef(nm, ty.isEmpty() ? "STRING" : ty));
            if (bool(f.get("is_pk")) && pk.isEmpty()) pk = nm;
        }
        return StarRocksDdlBuilder.build(db, str(t.get("name")), cols, pk, !pk.isEmpty());
    }

    /** 选了数据元但未显式给类型 → 从数据元带出（落地数据标准） */
    private String resolveType(long elementId, String given) {
        if (given != null && !given.isEmpty()) return given;
        if (elementId <= 0) return "";
        try {
            Map<String, Object> el = jdbc.queryForMap(
                    "SELECT data_type, length, precision_, scale_ FROM meta.gov_data_element WHERE id=?", elementId);
            return buildTypeStr(str(el.get("data_type")), num(el.get("length")), num(el.get("precision_")), num(el.get("scale_")));
        } catch (Exception e) { return ""; }
    }

    private static String buildTypeStr(String t, int len, int prec, int scale) {
        if (t == null || t.isEmpty()) return "";
        String u = t.toUpperCase();
        if (u.equals("VARCHAR") || u.equals("CHAR") || u.equals("STRING")) return len > 0 ? u + "(" + len + ")" : u;
        if (u.equals("DECIMAL") || u.equals("NUMERIC")) return u + "(" + (prec > 0 ? prec : 10) + "," + scale + ")";
        return u;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOrDefault(Object o, String def) { String s = str(o); return s.isEmpty() ? def : s; }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static boolean bool(Object o) { return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
}
