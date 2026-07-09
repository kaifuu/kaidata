package com.pharma.service.controller;

import com.pharma.service.access.meta.MetaCollectExecutor;
import com.pharma.service.access.meta.MetaCollectScheduler;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 元数据采集 [SYS_ADMIN]：采集任务 CRUD/执行/上下线 + 采集日志查询/清除。
 * 周期调度（online→scheduler.start）在阶段H接入 MetaCollectScheduler 后补齐；当前上下线仅置状态。
 */
@RestController
@RequestMapping("/api/data-gov/meta/collect")
@CrossOrigin(origins = "*")
public class DataMetaCollectController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MetaCollectExecutor executor;
    @Autowired private MetaCollectScheduler scheduler;

    // ==================== 任务 CRUD ====================

    @GetMapping("/job/list")
    public List<Map<String, Object>> listJobs() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> jobs = jdbc.queryForList(
                "SELECT id, name, ds_id, schema_filter, table_filter, cron, status, create_by, create_time, update_time " +
                        "FROM meta.gov_meta_collect_job ORDER BY id");
        Map<Long, String> dsName = dsNameMap();
        for (Map<String, Object> j : jobs) j.put("ds_name", dsName.getOrDefault(lng(j.get("ds_id")), ""));
        return jobs;
    }

    @PostMapping("/job")
    public Map<String, Object> createJob(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        saveJob(id, b, true);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/job")
    public Map<String, Object> updateJob(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        saveJob(id, b, false);
        scheduler.stop(id);
        if ("ONLINE".equals(str(b.getOrDefault("status", ""))) && !str(b.get("cron")).isEmpty()) {
            scheduler.start(id, str(b.get("cron")));
        }
        return Map.of("success", true);
    }

    @DeleteMapping("/job")
    public Map<String, Object> deleteJob(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_meta_collect_log WHERE job_id=?", id);
        jdbc.update("DELETE FROM meta.gov_meta_collect_job WHERE id=?", id);
        return Map.of("success", true);
    }

    private void saveJob(long id, Map<String, Object> b, boolean create) {
        String name = str(b.get("name"));
        long dsId = lng(b.get("ds_id"));
        String schemaFilter = str(b.get("schema_filter"));
        String tableFilter = str(b.get("table_filter"));
        String cron = str(b.get("cron"));
        String status = str(b.getOrDefault("status", "OFFLINE"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (create) {
            jdbc.update("INSERT INTO meta.gov_meta_collect_job" +
                            "(id, name, ds_id, schema_filter, table_filter, cron, status, create_by, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    id, name, dsId, schemaFilter, tableFilter, cron, status, currentUser(), now, now);
        } else {
            jdbc.update("UPDATE meta.gov_meta_collect_job SET name=?, ds_id=?, schema_filter=?, table_filter=?, cron=?, status=?, update_time=? WHERE id=?",
                    name, dsId, schemaFilter, tableFilter, cron, status, now, id);
        }
    }

    // ==================== 执行 / 上下线 ====================

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        return executor.run(jobId);
    }

    @PostMapping("/online")
    public Map<String, Object> online(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_meta_collect_job SET status='ONLINE', update_time=? WHERE id=?",
                new Timestamp(System.currentTimeMillis()), jobId);
        String cron = jdbc.queryForObject("SELECT cron FROM meta.gov_meta_collect_job WHERE id=?", String.class, jobId);
        if (cron != null && !cron.isEmpty()) scheduler.start(jobId, cron);
        return Map.of("success", true);
    }

    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        scheduler.stop(jobId);
        jdbc.update("UPDATE meta.gov_meta_collect_job SET status='OFFLINE', update_time=? WHERE id=?",
                new Timestamp(System.currentTimeMillis()), jobId);
        return Map.of("success", true);
    }

    // ==================== 采集日志 ====================

    @GetMapping("/run/list")
    public List<Map<String, Object>> runList(@RequestParam(required = false) Long jobId,
                                             @RequestParam(defaultValue = "100") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT id, job_id, start_time, end_time, status, tables_total, tables_added, tables_changed, " +
                "tables_removed, error_msg, triggered_by FROM meta.gov_meta_collect_log";
        if (jobId != null) {
            sql += " WHERE job_id=? ORDER BY id DESC LIMIT " + Math.min(limit, 500);
            return jdbc.queryForList(sql, jobId);
        }
        sql += " ORDER BY id DESC LIMIT " + Math.min(limit, 500);
        return jdbc.queryForList(sql);
    }

    @GetMapping("/run/detail")
    public Map<String, Object> runDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, job_id, start_time, end_time, status, tables_total, tables_added, " +
                "tables_changed, tables_removed, detail, error_msg, triggered_by FROM meta.gov_meta_collect_log WHERE id=?", id);
    }

    /** 清除日志（all=全部 / failed=失败 / before7d=7天前）。jobId 必传。 */
    @DeleteMapping("/run")
    public Map<String, Object> clearRuns(@RequestParam long jobId, @RequestParam(defaultValue = "all") String rule) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("DELETE FROM meta.gov_meta_collect_log WHERE job_id=?");
        List<Object> args = new ArrayList<>();
        args.add(jobId);
        if ("failed".equalsIgnoreCase(rule)) sql.append(" AND status<>'SUCCESS'");
        else if ("before7d".equalsIgnoreCase(rule)) sql.append(" AND start_time < DATE_SUB(NOW(), INTERVAL 7 DAY)");
        int n = jdbc.update(sql.toString(), args.toArray());
        return Map.of("success", true, "deleted", n);
    }

    // -------- 助手 --------
    private Map<Long, String> dsNameMap() {
        Map<Long, String> m = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT id, name FROM meta.ing_datasource")) {
                m.put(lng(r.get("id")), str(r.get("name")));
            }
        } catch (Exception ignored) {}
        return m;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
}
