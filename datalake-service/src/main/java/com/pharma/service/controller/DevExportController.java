package com.pharma.service.controller;

import com.pharma.service.access.develop.DevExportExecutor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据接出 [SYS_ADMIN]：导出定义 CRUD + 执行（写目标库 / REST 推送）+ 历史。 */
@RestController
@RequestMapping("/api/data-dev/export")
@CrossOrigin(origins = "*")
public class DevExportController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevExportExecutor executor;

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, source_ds_id, source_query, target_type, target_config, format, create_time FROM meta.dev_export ORDER BY id");
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.dev_export(id, name, source_ds_id, source_query, target_type, target_config, format, create_time) VALUES (?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), lng(b.get("source_ds_id")), str(b.get("source_query")),
                str(b.getOrDefault("target_type", "db")), str(b.get("target_config")), str(b.getOrDefault("format", "json")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.dev_export SET name=?, source_ds_id=?, source_query=?, target_type=?, target_config=?, format=? WHERE id=?",
                str(b.get("name")), lng(b.get("source_ds_id")), str(b.get("source_query")),
                str(b.get("target_type")), str(b.get("target_config")), str(b.get("format")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.dev_export_run WHERE export_id=?", id);
        jdbc.update("DELETE FROM meta.dev_export WHERE id=?", id);
        return Map.of("success", true);
    }
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return executor.run(id);
    }
    @GetMapping("/run-list")
    public List<Map<String, Object>> runList(@RequestParam long exportId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, export_id, status, rows_out, error_msg, run_time FROM meta.dev_export_run WHERE export_id=? ORDER BY id DESC LIMIT 50", exportId);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
