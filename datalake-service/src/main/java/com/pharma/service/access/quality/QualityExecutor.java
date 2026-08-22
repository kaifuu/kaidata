package com.pharma.service.access.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.security.AlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据质量执行核心：任务 → 逐规则算 metric/score/状态 → 聚合总分/等级/维度·表摘要 → 落 gov_quality_report。
 * <p>从 DataQualityController 抽出，供 Controller（手动 /run）与 QualityScheduler（周期）复用，避免循环依赖。
 * <p>P0 升级：① sample_rows>0 时统计窗口限制在前 N 行（大表防全扫）；② FAIL 自动抓取违规行样例
 * 并生成/刷新问题工单（gov_quality_issue，每规则至多一条未核销）；③ 复检 PASS 自动核销工单。
 */
@Component
public class QualityExecutor {

    private static final Logger log = LoggerFactory.getLogger(QualityExecutor.class);

    /** 违规行样例抓取上限（存 sample_json，够定位问题即可）。 */
    private static final int SAMPLE_LIMIT = 20;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private AlertService alertService;
    /** findAndRegisterModules：注册 JavaTimeModule，否则采样行里的 LocalDateTime（DATETIME 列）序列化抛异常。 */
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    /** 执行任务下所有规则并聚合报告。返回报告摘要（含 success/total/pass/fail/error）。 */
    public Map<String, Object> run(long taskId) {
        Map<String, Object> task = jdbc.queryForMap("SELECT id, name, rule_ids FROM meta.gov_quality_task WHERE id=?", taskId);
        List<Long> ruleIds = parseIds(str(task.get("rule_ids")));
        int pass = 0, fail = 0, error = 0;
        long reportId = System.currentTimeMillis();   // 预生成：工单需关联本次报告
        for (Long rid : ruleIds) {
            try {
                if (runRule(taskId, rid, reportId)) pass++; else fail++;
            } catch (Exception e) {
                error++;
                record(taskId, rid, "ERROR", 0, 0, 0, 0, 0, "", "", rootMsg(e));
            }
        }
        Map<String, Object> report = aggregateAndSaveReport(taskId, str(task.get("name")), ruleIds.size(), pass, fail, error, reportId);
        if (fail > 0 || error > 0) {
            try { alertService.raise("MINOR", "质量检测异常 task=" + taskId + " pass=" + pass + " fail=" + fail + " error=" + error); } catch (Exception ignored) {}
        }
        Map<String, Object> out = new LinkedHashMap<>(report);
        out.put("success", true);
        out.put("total", ruleIds.size());
        out.put("pass", pass);
        out.put("fail", fail);
        out.put("error", error);
        return out;
    }

    private boolean runRule(long taskId, long ruleId, long reportId) throws Exception {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT dimension, ds_id, table_name, column_name, expression, threshold, severity, sample_rows FROM meta.gov_quality_rule WHERE id=?", ruleId);
        String dim = normalizeDim(str(r.get("dimension")));
        String table = str(r.get("table_name"));
        String col = str(r.get("column_name"));
        String sev = strOrDefault(r.get("severity"), "MAJOR");
        double threshold = dbl(r.get("threshold"));
        long sampleRows = lng(r.get("sample_rows"));
        DataSourceDescriptor ds = loader.load(lng(r.get("ds_id")));
        registry.adapter(ds.type);
        DataSource pool = registry.getPool(ds);

        // 采样窗口：sampleRows>0 时所有统计限制在前 N 行（子查询窗口），否则全表
        String totalSql = sampleRows > 0
                ? "SELECT COUNT(*) FROM (SELECT 1 FROM " + table + " LIMIT " + sampleRows + ") w"
                : "SELECT COUNT(*) FROM " + table;
        long total = qLong(pool, totalSql);
        double value;
        long violate;
        double metric;
        boolean ok;
        String sampleSql = null;   // FAIL 时抓违规行样例的 SQL
        switch (dim) {
            case "COMPLETENESS" -> {
                String nullSql = sampleRows > 0
                        ? "SELECT COUNT(*) FROM (SELECT " + col + " AS c FROM " + table + " LIMIT " + sampleRows + ") w WHERE w.c IS NULL"
                        : "SELECT COUNT(*) FROM " + table + " WHERE " + col + " IS NULL";
                long nulls = qLong(pool, nullSql);
                violate = nulls;
                value = total == 0 ? 0 : (double) nulls / total;
                ok = value <= threshold;
                metric = total == 0 ? 1 : 1 - value;
                sampleSql = "SELECT * FROM " + table + " WHERE " + col + " IS NULL LIMIT " + SAMPLE_LIMIT;
            }
            case "UNIQUENESS" -> {
                String distinctSql = sampleRows > 0
                        ? "SELECT COUNT(DISTINCT c) FROM (SELECT " + col + " AS c FROM " + table + " LIMIT " + sampleRows + ") w"
                        : "SELECT COUNT(DISTINCT " + col + ") FROM " + table;
                long distinct = qLong(pool, distinctSql);
                violate = total - distinct;
                value = total == 0 ? 1 : (double) distinct / total;
                ok = value >= threshold;
                metric = value;
                sampleSql = "SELECT " + col + " AS dup_value, COUNT(*) AS dup_count FROM " + table + " GROUP BY " + col + " HAVING COUNT(*)>1 LIMIT " + SAMPLE_LIMIT;
            }
            case "TIMELINESS" -> {
                String maxSql = sampleRows > 0
                        ? "SELECT TIMESTAMPDIFF(HOUR, MAX(c), NOW()) FROM (SELECT " + col + " AS c FROM " + table + " LIMIT " + sampleRows + ") w"
                        : "SELECT TIMESTAMPDIFF(HOUR, MAX(" + col + "), NOW()) FROM " + table;
                double hours = qDouble(pool, maxSql);
                value = hours;
                violate = hours <= threshold ? 0 : 1;
                ok = hours <= threshold;
                metric = ok ? 1 : Math.max(0, 1 - (hours - threshold) / Math.max(threshold, 1));
            }
            default -> {   // VALIDITY / ACCURACY / CONSISTENCY（expression 当 WHERE，支持子查询实现跨表核验）
                String expr = str(r.get("expression"));
                if (expr.isEmpty()) {
                    violate = 0;
                } else {
                    String cntSql = sampleRows > 0
                            ? "SELECT COUNT(*) FROM (SELECT * FROM " + table + " LIMIT " + sampleRows + ") w WHERE " + expr
                            : "SELECT COUNT(*) FROM " + table + " WHERE " + expr;
                    violate = qLong(pool, cntSql);
                }
                value = violate;
                ok = violate <= threshold;
                metric = total == 0 ? 1 : 1 - (double) violate / total;
                if (!expr.isEmpty()) sampleSql = "SELECT * FROM " + table + " WHERE " + expr + " LIMIT " + SAMPLE_LIMIT;
            }
        }
        int score = (int) Math.round(metric * 100);
        record(taskId, ruleId, ok ? "PASS" : "FAIL", value, threshold, violate, total, score, table, sev, "");
        // 工单闭环：FAIL → 抓样例生成/刷新工单；复检 PASS → 自动核销
        if (ok) autoResolveIssue(ruleId);
        else upsertIssue(taskId, ruleId, reportId, table, dim, sev, pool, sampleSql, violate);
        return ok;
    }

    /** 试运行（dry-run）：不落结果、不动工单，返回指标 + 违规行样例。规则保存前验证表达式用。 */
    public Map<String, Object> dryRun(Map<String, Object> r) throws Exception {
        String dim = normalizeDim(str(r.get("dimension")));
        String table = str(r.get("table_name"));
        String col = str(r.get("column_name"));
        double threshold = dbl(r.get("threshold"));
        DataSourceDescriptor ds = loader.load(lng(r.get("ds_id")));
        registry.adapter(ds.type);
        DataSource pool = registry.getPool(ds);

        long total = qLong(pool, "SELECT COUNT(*) FROM " + table);
        long violate;
        String sampleSql = null;
        switch (dim) {
            case "COMPLETENESS" -> {
                violate = table.isEmpty() || col.isEmpty() ? 0 : qLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + col + " IS NULL");
                sampleSql = "SELECT * FROM " + table + " WHERE " + col + " IS NULL LIMIT " + SAMPLE_LIMIT;
            }
            case "UNIQUENESS" -> {
                long distinct = qLong(pool, "SELECT COUNT(DISTINCT " + col + ") FROM " + table);
                violate = total - distinct;
                sampleSql = "SELECT " + col + " AS dup_value, COUNT(*) AS dup_count FROM " + table + " GROUP BY " + col + " HAVING COUNT(*)>1 LIMIT " + SAMPLE_LIMIT;
            }
            case "TIMELINESS" -> {
                violate = qDouble(pool, "SELECT TIMESTAMPDIFF(HOUR, MAX(" + col + "), NOW()) FROM " + table) <= threshold ? 0 : 1;
            }
            default -> {
                String expr = str(r.get("expression"));
                if (expr.isEmpty()) violate = 0;
                else { violate = qLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + expr); sampleSql = "SELECT * FROM " + table + " WHERE " + expr + " LIMIT " + SAMPLE_LIMIT; }
            }
        }
        double metric = total == 0 ? 1 : Math.max(0, 1 - (double) violate / total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", dim);
        out.put("total", total);
        out.put("violate", violate);
        out.put("violateRate", total == 0 ? 0 : Math.round(violate * 10000.0 / total) / 100.0);
        out.put("score", (int) Math.round(metric * 100));
        out.put("sample", sampleSql == null ? List.of() : sampleRows(pool, sampleSql));
        return out;
    }

    // ===== 问题工单 =====

    /** FAIL：同一规则已有未核销工单则刷新样例/违规数，否则新建 OPEN 工单。 */
    private void upsertIssue(long taskId, long ruleId, long reportId, String table, String dim, String sev,
                             DataSource pool, String sampleSql, long violate) {
        try {
            String sampleJson = sampleSql == null ? "[]" : writeSampleSafely(pool, sampleSql);
            List<Map<String, Object>> open = jdbc.queryForList(
                    "SELECT id FROM meta.gov_quality_issue WHERE rule_id=? AND status IN ('OPEN','ASSIGNED')", ruleId);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            if (!open.isEmpty()) {
                jdbc.update("UPDATE meta.gov_quality_issue SET task_id=?, report_id=?, table_name=?, dimension=?, severity=?, sample_json=?, violate_count=? WHERE id=?",
                        taskId, reportId, table, dim, sev, sampleJson, violate, lng(open.get(0).get("id")));
            } else {
                long issueId = System.currentTimeMillis() + (long) (Math.random() * 1000);
                jdbc.update("INSERT INTO meta.gov_quality_issue(id, task_id, rule_id, report_id, table_name, dimension, severity, status, assignee, sample_json, violate_count, create_time) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        issueId, taskId, ruleId, reportId, table, dim, sev,
                        "OPEN", "", sampleJson, violate, now);
                try {
                    Long maxLog = jdbc.queryForObject("SELECT MAX(id) FROM meta.gov_quality_issue_log", Long.class);
                    jdbc.update("INSERT INTO meta.gov_quality_issue_log(id, issue_id, action, operator, comment, create_time) VALUES (?,?,?,?,?,?)",
                            (maxLog == null ? 0 : maxLog) + 1, issueId, "CREATE", "quality-engine",
                            "检测 FAIL 自动建单（违规 " + violate + " 行）", now);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("质量工单写入失败 rule={} table={}: {}", ruleId, table, e.toString());
        }
    }

    /** 复检通过自动核销该规则的未结工单。 */
    private void autoResolveIssue(long ruleId) {
        try {
            jdbc.update("UPDATE meta.gov_quality_issue SET status='RESOLVED', resolve_time=?, resolve_comment='复检通过·自动核销' " +
                    "WHERE rule_id=? AND status IN ('OPEN','ASSIGNED')", new Timestamp(System.currentTimeMillis()), ruleId);
        } catch (Exception e) {
            log.warn("质量工单核销失败 rule={}: {}", ruleId, e.toString());
        }
    }

    /** 采样行序列化；个别 JDBC 类型仍序列化失败时降级为空数组（保工单不保样例）。 */
    private String writeSampleSafely(DataSource pool, String sampleSql) {
        try {
            return json.writeValueAsString(sampleRows(pool, sampleSql));
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 执行查询并把前 N 行转成 List<Map>（列名做 key）。异常吞掉返回空表（样例抓取不阻塞主流程）。 */
    public List<Map<String, Object>> sampleRows(DataSource pool, String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                rows.add(row);
            }
        } catch (Exception ignored) {}
        return rows;
    }

    private void record(long taskId, long ruleId, String status, double value, double threshold,
                        long violate, long total, int score, String tableName, String severity, String err) {
        jdbc.update("INSERT INTO meta.gov_quality_result(id, task_id, rule_id, status, value, threshold, violate_count, total_count, score, table_name, severity, error_msg, run_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                System.currentTimeMillis() + (long) (Math.random() * 1000),
                taskId, ruleId, status, value, threshold, violate, total, score, tableName, severity, err, new Timestamp(System.currentTimeMillis()));
    }

    /** 聚合本次 run 结果：加权总分 = Σ(score*weight)/Σweight；落 gov_quality_report；返回报告摘要。 */
    private Map<String, Object> aggregateAndSaveReport(long taskId, String taskName, int totalRules, int pass, int fail, int error, long reportId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT res.status, res.score, res.severity, res.table_name, r.dimension " +
                        "FROM meta.gov_quality_result res LEFT JOIN meta.gov_quality_rule r ON r.id=res.rule_id " +
                        "WHERE res.task_id=? ORDER BY res.id DESC LIMIT " + Math.max(totalRules, 1), taskId);
        double wsum = 0, wtot = 0;
        boolean blockerFail = false;
        Map<String, double[]> dim = new LinkedHashMap<>();
        Map<String, double[]> tab = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String status = str(row.get("status"));
            int score = row.get("score") == null ? 0 : ((Number) row.get("score")).intValue();
            String sev = strOrDefault(row.get("severity"), "MAJOR");
            int w = weightOf(sev);
            if ("ERROR".equals(status)) score = 0;
            if ("FAIL".equals(status) && "BLOCKER".equals(sev)) blockerFail = true;
            wsum += score * (double) w; wtot += w;
            acc(dim, normalizeDim(str(row.get("dimension"))), score, w, status);
            acc(tab, emptyFallback(str(row.get("table_name")), "(未指定)"), score, w, status);
        }
        int overall = wtot == 0 ? 0 : (int) Math.round(wsum / wtot);
        String grade = gradeOf(overall, blockerFail);
        String dimSummary = summaryJson(dim);
        String tableSummary = summaryJson(tab);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO meta.gov_quality_report(id, task_id, task_name, run_time, overall_score, grade, total_rules, pass_count, fail_count, error_count, dim_summary, table_summary) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                reportId, taskId, taskName, now, overall, grade, totalRules, pass, fail, error, dimSummary, tableSummary);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", reportId);
        out.put("taskId", taskId);
        out.put("taskName", taskName);
        out.put("runTime", now);
        out.put("overallScore", overall);
        out.put("grade", grade);
        out.put("totalRules", totalRules);
        out.put("pass", pass);
        out.put("fail", fail);
        out.put("error", error);
        out.put("blockerFail", blockerFail);
        out.put("dimSummary", dimSummary);
        out.put("tableSummary", tableSummary);
        return out;
    }

    /** 查某表最新质量报告的 grade（经 gov_quality_result.table_name 反查最新 task → 最新 report）。无报告返回 null。 */
    public String latestGradeForTable(String tableName) {
        if (tableName == null || tableName.isEmpty()) return null;
        try {
            Long latestTask = jdbc.queryForObject(
                    "SELECT MAX(task_id) FROM meta.gov_quality_result WHERE table_name=? OR table_name LIKE CONCAT('%.', ?)",
                    Long.class, tableName, tableName);
            if (latestTask == null) return null;
            List<Map<String, Object>> reps = jdbc.queryForList(
                    "SELECT grade FROM meta.gov_quality_report WHERE task_id=? ORDER BY id DESC LIMIT 1", latestTask);
            return reps.isEmpty() ? null : str(reps.get(0).get("grade"));
        } catch (Exception e) {
            return null;
        }
    }

    private void acc(Map<String, double[]> m, String key, int score, int w, String status) {
        double[] a = m.computeIfAbsent(key, k -> new double[4]);
        a[0] += score * (double) w; a[1] += w;
        if ("PASS".equals(status)) a[2]++; else if ("FAIL".equals(status) || "ERROR".equals(status)) a[3]++;
    }
    public static String gradeOf(int overall, boolean blockerFail) {
        if (blockerFail) return "D";
        if (overall >= 90) return "A";
        if (overall >= 80) return "B";
        if (overall >= 60) return "C";
        return "D";
    }
    private String summaryJson(Map<String, double[]> m) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : m.entrySet()) {
            double[] a = e.getValue();
            int sc = a[1] == 0 ? 0 : (int) Math.round(a[0] / a[1]);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("score", sc);
            v.put("rules", (int) (a[2] + a[3]));
            v.put("pass", (int) a[2]);
            v.put("fail", (int) a[3]);
            o.put(e.getKey(), v);
        }
        try { return json.writeValueAsString(o); } catch (Exception ex) { return "{}"; }
    }

    private long qLong(DataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
    private double qDouble(DataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getDouble(1) : 0;
        }
    }
    /** 历史维度值归一到标准 code（修维度中英文不一致 bug）。 */
    public static String normalizeDim(String raw) {
        if (raw == null) return "VALIDITY";
        return switch (raw.trim()) {
            case "完整性" -> "COMPLETENESS";
            case "唯一性" -> "UNIQUENESS";
            case "自定义" -> "VALIDITY";
            default -> raw.trim().toUpperCase();
        };
    }
    private static int weightOf(String sev) {
        if (sev == null) return 2;
        return switch (sev) { case "BLOCKER" -> 4; case "CRITICAL" -> 3; case "MINOR" -> 1; default -> 2; };
    }
    private List<Long> parseIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) { try { out.add(Long.parseLong(s.trim())); } catch (Exception ignored) {} }
        return out;
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOrDefault(Object o, String def) { String s = str(o); return s.isEmpty() ? def : s; }
    private static String emptyFallback(String s, String def) { return (s == null || s.isBlank()) ? def : s; }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static double dbl(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).doubleValue(); try { return Double.parseDouble(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static String rootMsg(Throwable e) { Throwable c = e; for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause(); String m = c.getMessage(); return m == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + m; }
}
