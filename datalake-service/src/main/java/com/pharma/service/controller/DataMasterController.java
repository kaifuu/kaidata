package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 主数据 [SYS_ADMIN]：主数据定义（含字段定义 JSON）+ 主数据记录。 */
@RestController
@RequestMapping("/api/data-gov/master")
@CrossOrigin(origins = "*")
public class DataMasterController {

    @Autowired private JdbcTemplate jdbc;

    // ===== 主数据定义 =====
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, code, name, description, fields_json, create_time FROM meta.gov_master ORDER BY id");
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_master(id, code, name, description, fields_json, create_time) VALUES (?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("description")), str(b.get("fields_json")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_master SET code=?, name=?, description=?, fields_json=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("description")), str(b.get("fields_json")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_master_record WHERE master_id=?", id);
        jdbc.update("DELETE FROM meta.gov_master WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 主数据记录 =====
    @GetMapping("/record")
    public List<Map<String, Object>> listRecord(@RequestParam long masterId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, master_id, data_json, create_time FROM meta.gov_master_record WHERE master_id=? ORDER BY id DESC", masterId);
    }
    @PostMapping("/record")
    public Map<String, Object> createRecord(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_master_record(id, master_id, data_json, create_time) VALUES (?,?,?,?)",
                id, lng(b.get("master_id")), str(b.get("data_json")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @DeleteMapping("/record")
    public Map<String, Object> deleteRecord(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_master_record WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
