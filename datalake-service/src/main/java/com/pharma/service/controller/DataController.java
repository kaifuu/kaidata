package com.pharma.service.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 数据 API
 * <p>
 * 直接查 StarRocks 数仓，返回前端所需数据。数据是 Flink/Spark 实时/批计算后的结果，
 * 而非静态 CRUD——这是"大数据平台服务层"与旧管理后台的本质区别。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DataController {

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 环境监测（实时流，Flink 写入） ----------

    /** 各设备最新读数（实时监控看板） */
    @GetMapping("/env/latest")
    public List<Map<String, Object>> latestEnv() {
        // 每设备每指标取最新一条
        return jdbc.queryForList(
                "SELECT device_id, metric, value, min_val, max_val, ts " +
                        "FROM ods.ods_env_monitor " +
                        "WHERE (device_id, metric, ts) IN (" +
                        "  SELECT device_id, metric, MAX(ts) FROM ods.ods_env_monitor GROUP BY device_id, metric" +
                        ") ORDER BY device_id, metric");
    }

    /** 近期告警（Flink 实时判定写入） */
    @GetMapping("/env/alarms")
    public List<Map<String, Object>> alarms(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList(
                "SELECT device_id, metric, value, min_val, max_val, severity, ts " +
                        "FROM ods.ods_alarm ORDER BY ts DESC LIMIT ?", limit);
    }

    /** 设备历史趋势（某设备某指标近 N 条） */
    @GetMapping("/env/history")
    public List<Map<String, Object>> history(@RequestParam String deviceId,
                                             @RequestParam String metric,
                                             @RequestParam(defaultValue = "100") int limit) {
        return jdbc.queryForList(
                "SELECT ts, value, min_val, max_val FROM ods.ods_env_monitor " +
                        "WHERE device_id=? AND metric=? ORDER BY ts DESC LIMIT ?",
                deviceId, metric, limit);
    }

    // ---------- 批次质量追溯（批处理，Spark 写入 ADS） ----------

    /** 批次质量全景（追溯列表） */
    @GetMapping("/batch/quality")
    public List<Map<String, Object>> batchQuality(@RequestParam(required = false) String batchNo) {
        if (batchNo != null && !batchNo.isEmpty()) {
            return jdbc.queryForList(
                    "SELECT batch_no, material_code, quantity, batch_status, qc_pass, qc_result, qc_spec, ts " +
                            "FROM ads.ads_batch_quality WHERE batch_no=? ORDER BY ts DESC", batchNo);
        }
        return jdbc.queryForList(
                "SELECT batch_no, material_code, quantity, batch_status, qc_pass, qc_result, qc_spec, ts " +
                        "FROM ads.ads_batch_quality ORDER BY ts DESC LIMIT 100");
    }

    /** 环境合规达标率（Spark 汇总） */
    @GetMapping("/env/compliance")
    public List<Map<String, Object>> compliance() {
        return jdbc.queryForList(
                "SELECT device_id, metric, total_cnt, ok_cnt, compliance_rate " +
                        "FROM ads.ads_env_compliance ORDER BY device_id, metric");
    }

    /** 生产效能（Spark 按物料汇总，供效能看板） */
    @GetMapping("/production/efficiency")
    public List<Map<String, Object>> productionEfficiency() {
        return jdbc.queryForList(
                "SELECT material_code, material_name, total_batches, abnormal_batches, " +
                        "total_quantity, avg_qc_result, pass_rate " +
                        "FROM ads.ads_production_efficiency ORDER BY total_quantity DESC");
    }

    // ---------- 门户总览 ----------

    @GetMapping("/portal/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new HashMap<>();
        m.put("envRecords", count("SELECT COUNT(*) FROM ods.ods_env_monitor"));
        m.put("alarmCount", count("SELECT COUNT(*) FROM ods.ods_alarm"));
        m.put("batchCount", count("SELECT COUNT(*) FROM ods.ods_batch"));
        m.put("qcCount", count("SELECT COUNT(*) FROM ods.ods_qc"));
        m.put("pendingAlarms", count("SELECT COUNT(*) FROM ods.ods_alarm"));
        return m;
    }

    private long count(String sql) {
        try {
            return jdbc.queryForObject(sql, Long.class);
        } catch (Exception e) {
            return 0;
        }
    }
}
