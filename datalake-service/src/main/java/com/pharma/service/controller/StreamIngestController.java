package com.pharma.service.controller;

import com.pharma.service.access.kafka.JdbcToKafkaRunner;
import com.pharma.service.access.kafka.RoutineLoadManager;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 实时数据接入（Kafka 管道）[SYS_ADMIN]：
 * <ul>
 *   <li>KAFKA_TO_SR：建/停 ROUTINE LOAD，Kafka topic → StarRocks 表（复用现有入仓路径）</li>
 *   <li>JDBC_TO_KAFKA：定时轮询源 SQL → 投递 Kafka topic（秒级轮询，非真 CDC）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/data-access/stream")
@CrossOrigin(origins = "*")
public class StreamIngestController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoutineLoadManager routineLoad;
    @Autowired private JdbcToKafkaRunner jdbcToKafka;

    @GetMapping("/job/list")
    public List<Map<String, Object>> listJobs(@RequestParam(required = false) Long catalogId) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT id, name, type, source_ds_id, source_query, kafka_topic, " +
                "target_db, target_table, columns_json, schedule_cron, catalog_id, status, create_time, update_time " +
                "FROM meta.ing_stream_job";
        if (catalogId != null) return jdbc.queryForList(sql + " WHERE catalog_id=? ORDER BY id", catalogId);
        return jdbc.queryForList(sql + " ORDER BY id");
    }

    @PostMapping("/job")
    public Map<String, Object> createJob(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        jdbc.update("INSERT INTO meta.ing_stream_job" +
                        "(id, name, type, source_ds_id, source_query, kafka_topic, target_db, target_table, " +
                        "columns_json, schedule_cron, catalog_id, status, props, create_by, create_time, update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("type")), lng(b.get("source_ds_id")), str(b.get("source_query")),
                str(b.get("kafka_topic")), str(b.get("target_db")), str(b.get("target_table")),
                str(b.get("columns_json")), str(b.get("schedule_cron")), lng(b.get("catalog_id")), "STOPPED", str(b.get("props")),
                currentUser(), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/job")
    public Map<String, Object> updateJob(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("UPDATE meta.ing_stream_job SET name=?, type=?, source_ds_id=?, source_query=?, kafka_topic=?, " +
                        "target_db=?, target_table=?, columns_json=?, schedule_cron=?, catalog_id=?, props=?, update_time=? WHERE id=?",
                str(b.get("name")), str(b.get("type")), lng(b.get("source_ds_id")), str(b.get("source_query")),
                str(b.get("kafka_topic")), str(b.get("target_db")), str(b.get("target_table")),
                str(b.get("columns_json")), str(b.get("schedule_cron")), lng(b.get("catalog_id")), str(b.get("props")), now, id);
        return Map.of("success", true);
    }

    @DeleteMapping("/job")
    public Map<String, Object> deleteJob(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        stopInternal(id);
        jdbc.update("DELETE FROM meta.ing_stream_run WHERE job_id=?", id);
        jdbc.update("DELETE FROM meta.ing_stream_job WHERE id=?", id);
        return Map.of("success", true);
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT id, type, source_ds_id, source_query, kafka_topic, target_db, target_table, columns_json, schedule_cron " +
                        "FROM meta.ing_stream_job WHERE id=?", jobId);
        String type = str(job.get("type"));
        if ("KAFKA_TO_SR".equals(type)) {
            routineLoad.start(jobId, str(job.get("kafka_topic")), str(job.get("target_db")),
                    str(job.get("target_table")), str(job.get("columns_json")));
        } else if ("JDBC_TO_KAFKA".equals(type)) {
            jdbcToKafka.start(jobId, lng(job.get("source_ds_id")), str(job.get("source_query")),
                    str(job.get("kafka_topic")), parseSec(str(job.get("schedule_cron"))));
        } else {
            throw new IllegalArgumentException("未知实时作业类型：" + type);
        }
        jdbc.update("UPDATE meta.ing_stream_job SET status='RUNNING', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), jobId);
        return Map.of("success", true);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        stopInternal(jobId);
        jdbc.update("UPDATE meta.ing_stream_job SET status='STOPPED', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), jobId);
        return Map.of("success", true);
    }

    private void stopInternal(long jobId) {
        try {
            Map<String, Object> job = jdbc.queryForMap("SELECT type, target_db FROM meta.ing_stream_job WHERE id=?", jobId);
            String type = str(job.get("type"));
            if ("KAFKA_TO_SR".equals(type)) routineLoad.stop(jobId, str(job.get("target_db")));
            else if ("JDBC_TO_KAFKA".equals(type)) jdbcToKafka.stop(jobId);
        } catch (Exception ignored) {}
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> job = jdbc.queryForMap("SELECT type, target_db FROM meta.ing_stream_job WHERE id=?", jobId);
        String type = str(job.get("type"));
        String state = "JDBC_TO_KAFKA".equals(type)
                ? (jdbcToKafka.isRunning(jobId) ? "RUNNING" : "STOPPED")
                : routineLoad.state(jobId, str(job.get("target_db")));
        return Map.of("state", state);
    }

    @GetMapping("/run/list")
    public List<Map<String, Object>> runList(@RequestParam long jobId, @RequestParam(defaultValue = "50") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, job_id, start_time, end_time, status, rows_in, rows_out, error_msg " +
                "FROM meta.ing_stream_run WHERE job_id=? ORDER BY id DESC LIMIT " + Math.min(limit, 500), jobId);
    }

    @GetMapping("/routine-load/list")
    public List<Map<String, Object>> routineLoads() {
        Authz.require(Authz.SYS_ADMIN);
        return routineLoad.all();
    }

    // -------- 助手 --------
    private static int parseSec(String s) { try { return Math.max(Integer.parseInt(s.trim()), 1); } catch (Exception e) { return 30; } }
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
