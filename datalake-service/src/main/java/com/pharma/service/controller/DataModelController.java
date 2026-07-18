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
    @DeleteMapping("/table")
    public Map<String, Object> deleteTable(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_model_field WHERE table_id=?", id);
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

    /** 物理表逆向导入模型：从 gov_meta_table.columns_json 生成模型表+字段（pk 按 id/*_id 推断）。 */
    @PostMapping("/reverse")
    public Map<String, Object> reverse(@RequestParam long metaId, @RequestParam long modelId,
                                       @RequestParam(required = false, defaultValue = "ods") String layer) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> meta = jdbc.queryForMap("SELECT table_name, columns_json FROM meta.gov_meta_table WHERE id=?", metaId);
        long tableId = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_model_table(id, model_id, name, layer, description) VALUES (?,?,?,?,?)",
                tableId, modelId, str(meta.get("table_name")), str(layer), "逆向导入自 " + str(meta.get("table_name")));
        int n = 0;
        try {
            List<?> cols = json.readValue(str(meta.get("columns_json")), List.class);
            for (Object o : cols) {
                String nm, ty, cm;
                if (o instanceof Map) {
                    Map<?, ?> c = (Map<?, ?>) o;
                    nm = str(c.get("name")); ty = str(c.get("type")); cm = str(c.get("comment"));
                } else { nm = str(o); ty = ""; cm = ""; }
                if (nm.isEmpty()) continue;
                if (ty.isEmpty()) ty = "STRING";
                boolean pk = nm.equals("id") || nm.endsWith("_id");
                jdbc.update("INSERT INTO meta.gov_model_field(id, table_id, name, data_type, element_id, is_pk, nullable, comment) VALUES (?,?,?,?,?,?,?,?)",
                        System.currentTimeMillis() + (n++), tableId, nm, ty, 0, pk, true, cm);
            }
        } catch (Exception e) {
            return Map.of("success", false, "msg", "解析 columns_json 失败: " + e.getMessage());
        }
        return Map.of("success", true, "tableId", tableId, "fields", n);
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
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static boolean bool(Object o) { return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
}
