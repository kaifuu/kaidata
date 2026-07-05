package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.*;

/** 数据质量 [SYS_ADMIN]：质量规则 + 任务 + 执行（完整性/唯一性/自定义）+ 结果报告。 */
@RestController
@RequestMapping("/api/data-gov/quality")
@CrossOrigin(origins = "*")
public class DataQualityController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    // ===== 规则 =====
    @GetMapping("/rule")
    public List<Map<String, Object>> listRule(@RequestParam(required = false) String dimension) {
        Authz.require(Authz.SYS_ADMIN);
        if (dimension == null || dimension.isEmpty())
            return jdbc.queryForList("SELECT id, name, dimension, ds_id, table_name, column_name, expression, threshold, status, create_time FROM meta.gov_quality_rule ORDER BY id");
        return jdbc.queryForList("SELECT id, name, dimension, ds_id, table_name, column_name, expression, threshold, status, create_time FROM meta.gov_quality_rule WHERE dimension=? ORDER BY id", dimension);
    }
    @PostMapping("/rule")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_quality_rule(id, name, dimension, ds_id, table_name, column_name, expression, threshold, status, create_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("dimension")), lng(b.get("ds_id")), str(b.get("table_name")),
                str(b.get("column_name")), str(b.get("expression")), dbl(b.get("threshold")), str(b.getOrDefault("status", "ENABLED")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/rule")
    public Map<String, Object> updateRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_quality_rule SET name=?, dimension=?, ds_id=?, table_name=?, column_name=?, expression=?, threshold=?, status=? WHERE id=?",
                str(b.get("name")), str(b.get("dimension")), lng(b.get("ds_id")), str(b.get("table_name")),
                str(b.get("column_name")), str(b.get("expression")), dbl(b.get("threshold")), str(b.getOrDefault("status", "ENABLED")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping("/rule")
    public Map<String, Object> deleteRule(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_quality_rule WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 任务 =====
    @GetMapping("/task")
    public List<Map<String, Object>> listTask() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, rule_ids, cron, status, create_time FROM meta.gov_quality_task ORDER BY id");
    }
    @PostMapping("/task")
    public Map<String, Object> createTask(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_quality_task(id, name, rule_ids, cron, status, create_time) VALUES (?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("rule_ids")), str(b.get("cron")), str(b.getOrDefault("status", "ENABLED")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @DeleteMapping("/task")
    public Map<String, Object> deleteTask(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_quality_task WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 执行任务下所有规则。 */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> task = jdbc.queryForMap("SELECT id, name, rule_ids FROM meta.gov_quality_task WHERE id=?", taskId);
        List<Long> ruleIds = parseIds(str(task.get("rule_ids")));
        int pass = 0, fail = 0;
        for (Long rid : ruleIds) {
            try {
                boolean ok = runRule(taskId, rid);
                if (ok) pass++; else fail++;
            } catch (Exception e) {
                fail++;
                record(taskId, rid, "ERROR", 0, 0, 0, 0, rootMsg(e));
            }
        }
        return Map.of("success", true, "total", ruleIds.size(), "pass", pass, "fail", fail);
    }

    private boolean runRule(long taskId, long ruleId) throws Exception {
        Map<String, Object> r = jdbc.queryForMap("SELECT dimension, ds_id, table_name, column_name, expression, threshold FROM meta.gov_quality_rule WHERE id=?", ruleId);
        long dsId = lng(r.get("ds_id"));
        String table = str(r.get("table_name"));
        String col = str(r.get("column_name"));
        String dim = str(r.get("dimension"));
        double threshold = dbl(r.get("threshold"));
        DataSourceDescriptor ds = loader.load(dsId);
        DataSourceAdapter a = registry.adapter(ds.type);
        DataSource pool = registry.getPool(ds);

        long total = qLong(pool, "SELECT COUNT(*) FROM " + table);
        long violate;
        double value = 0;
        boolean ok;
        switch (dim) {
            case "完整性":
                violate = qLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + col + " IS NULL");
                value = total == 0 ? 0 : (double) violate / total;       // 空率
                ok = value <= threshold;                                  // threshold=最大允许空率
                break;
            case "唯一性":
                long distinct = qLong(pool, "SELECT COUNT(DISTINCT " + col + ") FROM " + table);
                violate = total - distinct;                                // 重复数
                value = total == 0 ? 1 : (double) distinct / total;       // 唯一率
                ok = value >= threshold;                                   // threshold=最低唯一率
                break;
            default: // 自定义：expression 当 WHERE 子句，统计违规行
                String expr = str(r.get("expression"));
                violate = expr.isEmpty() ? 0 : qLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + expr);
                value = violate;
                ok = violate <= threshold;
        }
        record(taskId, ruleId, ok ? "PASS" : "FAIL", value, threshold, violate, total, "");
        return ok;
    }

    private void record(long taskId, long ruleId, String status, double value, double threshold,
                        long violate, long total, String err) {
        jdbc.update("INSERT INTO meta.gov_quality_result(id, task_id, rule_id, status, value, threshold, violate_count, total_count, error_msg, run_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)", System.currentTimeMillis() + (long) (Math.random() * 1000),
                taskId, ruleId, status, value, threshold, violate, total, err, new Timestamp(System.currentTimeMillis()));
    }

    @GetMapping("/result")
    public List<Map<String, Object>> result(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT res.id, res.task_id, res.rule_id, r.name AS rule_name, r.dimension, res.status, " +
                "res.value, res.threshold, res.violate_count, res.total_count, res.error_msg, res.run_time " +
                "FROM meta.gov_quality_result res LEFT JOIN meta.gov_quality_rule r ON r.id=res.rule_id " +
                "WHERE res.task_id=? ORDER BY res.id DESC", taskId);
    }

    // -------- 助手 --------
    private long qLong(DataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
    private List<Long> parseIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) { try { out.add(Long.parseLong(s.trim())); } catch (Exception ignored) {} }
        return out;
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static double dbl(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).doubleValue(); try { return Double.parseDouble(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static String rootMsg(Throwable e) { Throwable c = e; for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause(); String m = c.getMessage(); return m == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + m; }
}
