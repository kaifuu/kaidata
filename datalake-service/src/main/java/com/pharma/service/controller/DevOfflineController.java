package com.pharma.service.controller;

import com.pharma.service.access.develop.DevOfflineExecutor;
import com.pharma.service.access.develop.DevOfflineScheduler;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.sql.Timestamp;
import java.util.*;

/**
 * 离线开发 [SYS_ADMIN]：分类树下的周期 SQL/ETL 任务（4 类作业），CRUD/执行/上下线(调度)/日志 + jar 上传。
 * <p>job_type：jdbc_sql（兼容旧）/ flink_sql / flink_jar / flink_dag / kettle_hop。
 */
@RestController
@RequestMapping("/api/data-dev/offline")
@CrossOrigin(origins = "*")
public class DevOfflineController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevOfflineExecutor executor;
    @Autowired private DevOfflineScheduler scheduler;
    @Value("${pharma.engines.jar-dir:data/flink-jars}") private String jarDir;
    @Autowired private com.pharma.service.access.meta.LineageExtractor lineageExtractor;

    // ===== 任务 CRUD =====
    @GetMapping("/task/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long catalogId,
                                          @RequestParam(required = false) String kw,
                                          @RequestParam(required = false) String jobType) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id, name, catalog_id, datasource_id, job_type, cron, status, create_by, create_time, update_time FROM meta.dev_offline_task WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (catalogId != null) { sql.append(" AND catalog_id=?"); args.add(catalogId); }
        if (kw != null && !kw.isEmpty()) { sql.append(" AND name LIKE ?"); args.add("%" + kw + "%"); }
        if (jobType != null && !jobType.isEmpty()) { sql.append(" AND job_type=?"); args.add(jobType); }
        sql.append(" ORDER BY id");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/task/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, name, catalog_id, datasource_id, job_type, sql_content, dag_json, config_json, cron, status, create_by, create_time, update_time FROM meta.dev_offline_task WHERE id=?", id);
    }

    @PostMapping("/task")
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        saveTask(id, b, true);
        try { lineageExtractor.rebuild("DEV_TASK", id); } catch (Exception ignored) {}
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/task")
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        saveTask(id, b, false);
        try { lineageExtractor.rebuild("DEV_TASK", id); } catch (Exception ignored) {}
        scheduler.stop(id);
        if ("ONLINE".equals(str(b.getOrDefault("status", ""))) && !str(b.get("cron")).isEmpty()) {
            scheduler.start(id, str(b.get("cron")));
        }
        return Map.of("success", true);
    }

    @DeleteMapping("/task")
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        scheduler.stop(id);
        jdbc.update("DELETE FROM meta.dev_offline_run WHERE task_id=?", id);
        jdbc.update("DELETE FROM meta.dev_offline_task WHERE id=?", id);
        return Map.of("success", true);
    }

    private void saveTask(long id, Map<String, Object> b, boolean create) {
        String name = str(b.get("name"));
        long catalogId = lng(b.get("catalog_id"));
        long dsId = lng(b.get("datasource_id"));
        String jobType = str(b.getOrDefault("job_type", "jdbc_sql"));
        String sqlContent = str(b.get("sql_content"));
        String dagJson = str(b.get("dag_json"));
        String configJson = str(b.get("config_json"));
        String cron = str(b.get("cron"));
        String status = str(b.getOrDefault("status", "OFFLINE"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (create) {
            jdbc.update("INSERT INTO meta.dev_offline_task(id, name, catalog_id, datasource_id, job_type, sql_content, dag_json, config_json, cron, status, create_by, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, name, catalogId, dsId, jobType, sqlContent, dagJson, configJson, cron, status, currentUser(), now, now);
        } else {
            jdbc.update("UPDATE meta.dev_offline_task SET name=?, catalog_id=?, datasource_id=?, job_type=?, sql_content=?, dag_json=?, config_json=?, cron=?, status=?, update_time=? WHERE id=?",
                    name, catalogId, dsId, jobType, sqlContent, dagJson, configJson, cron, status, now, id);
        }
    }

    // ===== jar 上传/列表（FlinkJar 作业用，存本地 ${pharma.engines.jar-dir}）=====
    @PostMapping("/jar/upload")
    public Map<String, Object> uploadJar(@RequestParam("file") MultipartFile file) {
        Authz.require(Authz.SYS_ADMIN);
        if (file == null || file.isEmpty()) throw new RuntimeException("未提供文件");
        File dir = new File(jarDir);
        if (!dir.exists()) dir.mkdirs();
        String name = file.getOriginalFilename();
        if (name == null || name.isEmpty() || !name.endsWith(".jar")) {
            name = "job-" + Long.toHexString(System.currentTimeMillis()) + ".jar";
        }
        try {
            file.transferTo(new File(dir, name).getAbsoluteFile());
        } catch (Exception e) {
            throw new RuntimeException("jar 保存失败: " + e.getMessage(), e);
        }
        return Map.of("success", true, "jarName", name, "size", file.getSize());
    }

    @GetMapping("/jar/list")
    public List<Map<String, Object>> listJars() {
        Authz.require(Authz.SYS_ADMIN);
        File dir = new File(jarDir);
        List<Map<String, Object>> out = new ArrayList<>();
        if (dir.exists()) {
            File[] fs = dir.listFiles((d, n) -> n.endsWith(".jar"));
            if (fs != null) {
                Arrays.sort(fs, Comparator.comparingLong(File::lastModified).reversed());
                for (File f : fs) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", f.getName());
                    m.put("size", f.length());
                    out.add(m);
                }
            }
        }
        return out;
    }

    // ===== 执行 / 上下线 =====
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return executor.run(id);
    }

    @PostMapping("/online")
    public Map<String, Object> online(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.dev_offline_task SET status='ONLINE', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), id);
        String cron = jdbc.queryForObject("SELECT cron FROM meta.dev_offline_task WHERE id=?", String.class, id);
        if (cron != null && !cron.isEmpty()) scheduler.start(id, cron);
        return Map.of("success", true);
    }

    @PostMapping("/offline")
    public Map<String, Object> offline(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        scheduler.stop(id);
        jdbc.update("UPDATE meta.dev_offline_task SET status='OFFLINE', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), id);
        return Map.of("success", true);
    }

    // ===== 日志 =====
    @GetMapping("/run/list")
    public List<Map<String, Object>> runList(@RequestParam(required = false) Long taskId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "100") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id, task_id, start_time, end_time, status, rows_read, cols, engine_job_id, error_msg, triggered_by FROM meta.dev_offline_run");
        List<Object> args = new ArrayList<>();
        List<String> w = new ArrayList<>();
        if (taskId != null) { w.add(" task_id=?"); args.add(taskId); }
        if (status != null && !status.isEmpty()) { w.add(" status=?"); args.add(status); }
        if (!w.isEmpty()) { sql.append(" WHERE"); sql.append(String.join(" AND", w)); }
        sql.append(" ORDER BY id DESC LIMIT ").append(Math.min(limit, 500));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/run/detail")
    public Map<String, Object> runDetail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT id, task_id, start_time, end_time, status, rows_read, cols, engine_job_id, error_msg, log_text, triggered_by FROM meta.dev_offline_run WHERE id=?", id);
    }

    @DeleteMapping("/run")
    public Map<String, Object> clearRuns(@RequestParam long taskId, @RequestParam(defaultValue = "all") String rule) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("DELETE FROM meta.dev_offline_run WHERE task_id=?");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        if ("failed".equalsIgnoreCase(rule)) sql.append(" AND status<>'SUCCESS'");
        else if ("before7d".equalsIgnoreCase(rule)) sql.append(" AND start_time < DATE_SUB(NOW(), INTERVAL 7 DAY)");
        int n = jdbc.update(sql.toString(), args.toArray());
        return Map.of("success", true, "deleted", n);
    }

    // -------- 助手 --------
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
