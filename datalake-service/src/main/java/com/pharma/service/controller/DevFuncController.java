package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 函数管理 [SYS_ADMIN]：内置函数登记 + 自定义函数（UDF）登记。 */
@RestController
@RequestMapping("/api/data-dev/func")
@CrossOrigin(origins = "*")
public class DevFuncController {

    @Autowired private JdbcTemplate jdbc;

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String funcType) {
        Authz.require(Authz.SYS_ADMIN);
        if (funcType == null || funcType.isEmpty())
            return jdbc.queryForList("SELECT id, name, func_type, language, return_type, description, create_time FROM meta.dev_function ORDER BY id");
        return jdbc.queryForList("SELECT id, name, func_type, language, return_type, description, create_time FROM meta.dev_function WHERE func_type=? ORDER BY id", funcType);
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.dev_function(id, name, func_type, language, body, return_type, description, create_time) VALUES (?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.getOrDefault("func_type", "UDF")), str(b.getOrDefault("language", "SQL")),
                str(b.get("body")), str(b.get("return_type")), str(b.get("description")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.dev_function SET name=?, func_type=?, language=?, body=?, return_type=?, description=? WHERE id=?",
                str(b.get("name")), str(b.getOrDefault("func_type", "UDF")), str(b.getOrDefault("language", "SQL")),
                str(b.get("body")), str(b.get("return_type")), str(b.get("description")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, name, func_type, language, body, return_type, description FROM meta.dev_function WHERE id=?", id);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.dev_function WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
