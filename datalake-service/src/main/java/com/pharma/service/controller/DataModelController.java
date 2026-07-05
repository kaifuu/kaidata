package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据模型 [SYS_ADMIN]：模型 + 模型表 + 模型字段。 */
@RestController
@RequestMapping("/api/data-gov/model")
@CrossOrigin(origins = "*")
public class DataModelController {

    @Autowired private JdbcTemplate jdbc;

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
        return jdbc.queryForList("SELECT id, table_id, name, data_type, element_id, is_pk, nullable, comment FROM meta.gov_model_field WHERE table_id=? ORDER BY id", tableId);
    }
    @PostMapping("/field")
    public Map<String, Object> createField(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_model_field(id, table_id, name, data_type, element_id, is_pk, nullable, comment) VALUES (?,?,?,?,?,?,?,?)",
                id, lng(b.get("table_id")), str(b.get("name")), str(b.get("data_type")), lng(b.get("element_id")),
                bool(b.get("is_pk")), bool(b.get("nullable")), str(b.get("comment")));
        return Map.of("success", true, "id", id);
    }
    @DeleteMapping("/field")
    public Map<String, Object> deleteField(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_model_field WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static boolean bool(Object o) { return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
}
