package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据标准 [SYS_ADMIN]：数据元 + 代码集 + 代码项。 */
@RestController
@RequestMapping("/api/data-gov/std")
@CrossOrigin(origins = "*")
public class DataStdController {

    @Autowired private JdbcTemplate jdbc;

    // ===== 数据元 =====
    @GetMapping("/element")
    public List<Map<String, Object>> listElement() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, code, name, data_type, length, precision_, scale_, definition, value_domain, status, create_time FROM meta.gov_data_element ORDER BY id");
    }
    @PostMapping("/element")
    public Map<String, Object> createElement(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_data_element(id, code, name, data_type, length, precision_, scale_, definition, value_domain, status, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("data_type")), num(b.get("length")), num(b.get("precision_")), num(b.get("scale_")),
                str(b.get("definition")), str(b.get("value_domain")), str(b.getOrDefault("status", "NORMAL")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/element")
    public Map<String, Object> updateElement(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        jdbc.update("UPDATE meta.gov_data_element SET code=?, name=?, data_type=?, length=?, precision_=?, scale_=?, definition=?, value_domain=?, status=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("data_type")), num(b.get("length")), num(b.get("precision_")), num(b.get("scale_")),
                str(b.get("definition")), str(b.get("value_domain")), str(b.getOrDefault("status", "NORMAL")), id);
        return Map.of("success", true);
    }
    @DeleteMapping("/element")
    public Map<String, Object> deleteElement(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_data_element WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 代码集 =====
    @GetMapping("/code-set")
    public List<Map<String, Object>> listCodeSet() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, code, name, description, status, create_time FROM meta.gov_code_set ORDER BY id");
    }
    @PostMapping("/code-set")
    public Map<String, Object> createCodeSet(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_code_set(id, code, name, description, status, create_time) VALUES (?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("description")), str(b.getOrDefault("status", "NORMAL")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/code-set")
    public Map<String, Object> updateCodeSet(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_code_set SET code=?, name=?, description=?, status=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("description")), str(b.getOrDefault("status", "NORMAL")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping("/code-set")
    public Map<String, Object> deleteCodeSet(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_code_item WHERE set_id=?", id);
        jdbc.update("DELETE FROM meta.gov_code_set WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 代码项 =====
    @GetMapping("/code-item")
    public List<Map<String, Object>> listCodeItem(@RequestParam long setId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, set_id, code, name, sort FROM meta.gov_code_item WHERE set_id=? ORDER BY sort, id", setId);
    }
    @PostMapping("/code-item")
    public Map<String, Object> createCodeItem(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_code_item(id, set_id, code, name, sort) VALUES (?,?,?,?,?)",
                id, lng(b.get("set_id")), str(b.get("code")), str(b.get("name")), num(b.get("sort")));
        return Map.of("success", true, "id", id);
    }
    @DeleteMapping("/code-item")
    public Map<String, Object> deleteCodeItem(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_code_item WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
