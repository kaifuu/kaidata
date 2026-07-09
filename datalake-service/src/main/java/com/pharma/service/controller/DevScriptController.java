package com.pharma.service.controller;

import com.pharma.service.access.develop.DevScriptExecutor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据开发 [SYS_ADMIN]：SQL/脚本 CRUD + 执行 + 历史。 */
@RestController
@RequestMapping("/api/data-dev/script")
@CrossOrigin(origins = "*")
public class DevScriptController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor executor;

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, script_type, datasource_id, catalog_id, content, description, create_time FROM meta.dev_script ORDER BY id");
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.dev_script(id, name, script_type, datasource_id, catalog_id, content, description, create_time) VALUES (?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.getOrDefault("script_type", "SQL")), lng(b.get("datasource_id")),
                lng(b.get("catalog_id")), str(b.get("content")), str(b.get("description")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.dev_script SET name=?, script_type=?, datasource_id=?, catalog_id=?, content=?, description=? WHERE id=?",
                str(b.get("name")), str(b.getOrDefault("script_type", "SQL")), lng(b.get("datasource_id")),
                lng(b.get("catalog_id")), str(b.get("content")), str(b.get("description")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.dev_script_run WHERE script_id=?", id);
        jdbc.update("DELETE FROM meta.dev_script WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 执行：传 {id} 执行已保存脚本，或 {datasource_id, content} 临时执行。 */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long scriptId = lng(b.get("id"));
        if (scriptId > 0) return executor.runScript(scriptId);
        return executor.runAdhoc(lng(b.get("datasource_id")), str(b.get("content")));
    }

    @GetMapping("/run-list")
    public List<Map<String, Object>> runList(@RequestParam long scriptId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, script_id, status, rows_read, cols, error_msg, run_time FROM meta.dev_script_run WHERE script_id=? ORDER BY id DESC LIMIT 50", scriptId);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
