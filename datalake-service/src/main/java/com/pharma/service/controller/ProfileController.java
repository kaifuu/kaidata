package com.pharma.service.controller;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.profile.AutoModler;
import com.pharma.service.access.profile.ProfileExecutor;
import com.pharma.service.access.profile.ProfileScheduler;
import com.pharma.service.access.util.SqlBuilder;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据探查 [SYS_ADMIN]：探查任务 CRUD/执行/上下线 + 数据源树/字段 + 日志 + 记录 + 版本对比。
 * MVP：层级=目标库下拉、数据元=JSON、告警=审计/预警、模版=内嵌cron秒数。
 */
@RestController
@RequestMapping("/api/data-access/profile")
@CrossOrigin(origins = "*")
public class ProfileController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private ProfileExecutor executor;
    @Autowired private ProfileScheduler scheduler;
    @Autowired private AutoModler autoModler;

    // ==================== 任务 CRUD ====================

    @GetMapping("/job/list")
    public List<Map<String, Object>> listJobs() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, source_ds_id, target_db, first_create_table, " +
                "alert_enabled, extra_columns, cron, status, create_time, update_time FROM meta.ing_profile_job ORDER BY id");
    }

    @GetMapping("/job/detail")
    public Map<String, Object> jobDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT id, name, source_ds_id, target_db, first_create_table, alert_enabled, extra_columns, cron, status " +
                        "FROM meta.ing_profile_job WHERE id=?", id);
        job.put("tables", jdbc.queryForList(
                "SELECT id, table_name, is_view, columns_config FROM meta.ing_profile_table WHERE job_id=?", id));
        return job;
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
        // 若仍 ONLINE 且配 cron，重启周期
        if ("ONLINE".equals(str(b.getOrDefault("status", ""))) && !str(b.get("cron")).isEmpty()) {
            scheduler.start(id, str(b.get("cron")));
        }
        return Map.of("success", true);
    }

    private void saveJob(long id, Map<String, Object> b, boolean create) {
        Timestamp now = new Timestamp(id);
        String name = str(b.get("name"));
        long sourceDsId = lng(b.get("source_ds_id"));
        String targetDb = str(b.get("target_db"));
        boolean firstCreate = bool(b.get("first_create_table"));
        boolean alert = bool(b.get("alert_enabled"));
        String extra = str(b.get("extra_columns"));
        String cron = str(b.get("cron"));
        String status = str(b.getOrDefault("status", "OFFLINE"));
        if (create) {
            jdbc.update("INSERT INTO meta.ing_profile_job" +
                            "(id, name, source_ds_id, target_db, first_create_table, alert_enabled, extra_columns, cron, status, tenant_id, create_by, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, name, sourceDsId, targetDb, firstCreate, alert, extra, cron, status,
                    lng(b.get("tenant_id")), currentUser(), now, now);
        } else {
            jdbc.update("UPDATE meta.ing_profile_job SET name=?, source_ds_id=?, target_db=?, first_create_table=?, " +
                            "alert_enabled=?, extra_columns=?, cron=?, status=?, update_time=? WHERE id=?",
                    name, sourceDsId, targetDb, firstCreate, alert, extra, cron, status, new Timestamp(System.currentTimeMillis()), id);
        }
        // 表配置：删旧插新
        jdbc.update("DELETE FROM meta.ing_profile_table WHERE job_id=?", id);
        for (Object o : (List<?>) b.getOrDefault("tables", List.of())) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) o;
            jdbc.update("INSERT INTO meta.ing_profile_table(id, job_id, table_name, is_view, columns_config) VALUES (?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), id,
                    str(t.get("table_name")), bool(t.get("is_view")), str(t.get("columns_config")));
        }
    }

    @DeleteMapping("/job")
    public Map<String, Object> deleteJob(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        scheduler.stop(id);
        jdbc.update("DELETE FROM meta.ing_profile_table WHERE job_id=?", id);
        jdbc.update("DELETE FROM meta.ing_profile_run WHERE job_id=?", id);
        jdbc.update("DELETE FROM meta.ing_profile_job WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 数据源树 / 字段 / 目标表检查 ====================

    @GetMapping("/tables")
    public List<Map<String, Object>> tables(@RequestParam long id, @RequestParam(required = false) String schema) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds = loader.load(id);
        DataSourceAdapter a = registry.adapter(ds.type);
        return a.listTables(registry.getPool(ds), schema);
    }

    @GetMapping("/columns")
    public List<Map<String, Object>> columns(@RequestParam long id, @RequestParam String table) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds = loader.load(id);
        DataSourceAdapter a = registry.adapter(ds.type);
        String[] sp = SqlBuilder.splitTable(table);
        return a.describeTable(registry.getPool(ds), sp[0], sp[1]);
    }

    @GetMapping("/target-exists")
    public Map<String, Object> targetExists(@RequestParam String db, @RequestParam String table) {
        Authz.require(Authz.SYS_ADMIN);
        String t = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        return Map.of("exists", autoModler.tableExists(db, t));
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
        jdbc.update("UPDATE meta.ing_profile_job SET status='ONLINE', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), jobId);
        String cron = jdbc.queryForObject("SELECT cron FROM meta.ing_profile_job WHERE id=?", String.class, jobId);
        if (cron != null && !cron.isEmpty()) scheduler.start(jobId, cron);
        return Map.of("success", true);
    }

    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        scheduler.stop(jobId);
        jdbc.update("UPDATE meta.ing_profile_job SET status='OFFLINE', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), jobId);
        return Map.of("success", true);
    }

    // ==================== 日志 ====================

    @GetMapping("/run/list")
    public List<Map<String, Object>> runList(@RequestParam(required = false) Long jobId,
                                             @RequestParam(defaultValue = "100") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT id, job_id, start_time, end_time, status, tables_changed, tables_total, error_msg, triggered_by " +
                "FROM meta.ing_profile_run";
        List<Object> args = new ArrayList<>();
        if (jobId != null) { sql += " WHERE job_id=?"; args.add(jobId); }
        sql += " ORDER BY id DESC LIMIT " + Math.min(limit, 500);
        return jobId != null ? jdbc.queryForList(sql, jobId) : jdbc.queryForList(sql);
    }

    @GetMapping("/run/detail")
    public Map<String, Object> runDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, job_id, start_time, end_time, status, tables_changed, tables_total, error_msg, log_text " +
                "FROM meta.ing_profile_run WHERE id=?", id);
    }

    // ==================== 探查记录 + 版本对比 ====================

    @GetMapping("/record/list")
    public List<Map<String, Object>> recordList(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, job_id, table_name, version_n, run_id, created_time " +
                "FROM meta.ing_profile_snapshot WHERE job_id=? ORDER BY table_name, version_n DESC", jobId);
    }

    @GetMapping("/record/snapshot")
    public Map<String, Object> snapshot(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, job_id, table_name, version_n, columns_json, stats_json, run_id, created_time " +
                "FROM meta.ing_profile_snapshot WHERE id=?", id);
    }

    @GetMapping("/diff/list")
    public List<Map<String, Object>> diffList(@RequestParam long jobId, @RequestParam String tableName) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, snapshot_id, added, removed, type_changed, created_time " +
                "FROM meta.ing_profile_diff WHERE job_id=? AND table_name=? ORDER BY id DESC", jobId, tableName);
    }

    /** 两版本字段对比：取两个 snapshot 的 columns_json，前端高亮差异。 */
    @GetMapping("/diff/compare")
    public Map<String, Object> diffCompare(@RequestParam long jobId, @RequestParam String tableName,
                                           @RequestParam int v1, @RequestParam int v2) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", snapshotByVersion(jobId, tableName, v1));
        out.put("v2", snapshotByVersion(jobId, tableName, v2));
        return out;
    }

    private Map<String, Object> snapshotByVersion(long jobId, String table, int ver) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version_n, columns_json, stats_json, created_time FROM meta.ing_profile_snapshot " +
                        "WHERE job_id=? AND table_name=? AND version_n=? ORDER BY id DESC LIMIT 1", jobId, table, ver);
        return rows.isEmpty() ? Map.of("version_n", ver, "columns_json", "[]", "stats_json", "{}") : rows.get(0);
    }

    // -------- 助手 --------
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
    private static boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        String s = String.valueOf(o);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
}
