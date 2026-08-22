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
    private final ObjectMapper json = new ObjectMapper();

    // ===== 模型 =====
    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String domain) {
        Authz.require(Authz.SYS_ADMIN);
        if (domain == null || domain.isEmpty()) return jdbc.queryForList("SELECT id, name, domain, model_type, description, status, create_time FROM meta.gov_model ORDER BY id");
        return jdbc.queryForList("SELECT id, name, domain, model_type, description, status, create_time FROM meta.gov_model WHERE domain=? ORDER BY id", domain);
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

    /** 生成模型表的 StarRocks 建表 DDL（复用 StarRocksDdlBuilder）。db=model_table.layer。 */
    @GetMapping("/table/ddl")
    public Map<String, Object> generateDdl(@RequestParam long tableId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> t = jdbc.queryForMap("SELECT name, layer FROM meta.gov_model_table WHERE id=?", tableId);
        return Map.of("ddl", buildDdl(tableId), "db", str(t.get("layer")), "table", str(t.get("name")));
    }

    /** 一键建物理表：生成 DDL → 在目标数据源执行（复用 DevScriptExecutor）。 */
    @PostMapping("/table/create-physical")
    public Map<String, Object> createPhysical(@RequestParam long tableId, @RequestParam long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        String ddl = buildDdl(tableId);
        Map<String, Object> r = scriptExecutor.executeSql(dsId, ddl);
        boolean ok = "SUCCESS".equals(str(r.get("status")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", ok);
        out.put("msg", ok ? "建表成功" : str(r.get("msg")));
        out.put("ddl", ddl);
        return out;
    }

    /**
     * 物理表逆向导入模型（增强）：从 gov_meta_table.columns_json 生成模型表+字段。
     * <ul>
     *   <li>PK：优先读列元数据 key 标记（PRI/key=true），无标记再按 id/*_id 推断</li>
     *   <li>数据元自动匹配：列名（含注释）↔数据元相似度（复用 Std 的 similarity），≥80 自动挂标准</li>
     * </ul>
     */
    @PostMapping("/reverse")
    public Map<String, Object> reverse(@RequestParam long metaId, @RequestParam long modelId,
                                       @RequestParam(required = false, defaultValue = "ods") String layer) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> meta = jdbc.queryForMap("SELECT table_name, columns_json FROM meta.gov_meta_table WHERE id=?", metaId);
        // 数据元池（相似度匹配用）
        List<Map<String, Object>> elements = jdbc.queryForList(
                "SELECT id, name, en_name FROM meta.gov_data_element WHERE status='NORMAL'");
        long tableId = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_model_table(id, model_id, name, layer, description) VALUES (?,?,?,?,?)",
                tableId, modelId, str(meta.get("table_name")), str(layer), "逆向导入自 " + str(meta.get("table_name")));
        int n = 0, matched = 0;
        try {
            List<?> cols = json.readValue(str(meta.get("columns_json")), List.class);
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
                    int s = DataStdController.similarity(cm.isEmpty() ? nm : cm + nm, str(e.get("name")), str(e.get("en_name")));
                    if (s >= 80) { elementId = lng(e.get("id")); matched++; break; }
                }
                jdbc.update("INSERT INTO meta.gov_model_field(id, table_id, name, data_type, element_id, is_pk, nullable, comment) VALUES (?,?,?,?,?,?,?,?)",
                        System.currentTimeMillis() + (n++), tableId, nm, ty, elementId, pk, !pk, cm);
            }
        } catch (Exception e) {
            return Map.of("success", false, "msg", "解析 columns_json 失败: " + e.getMessage());
        }
        return Map.of("success", true, "tableId", tableId, "fields", n, "stdMatched", matched);
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
