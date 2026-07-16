package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.security.Authz;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.net.URLEncoder;
import java.util.*;

/**
 * 数据质量 [SYS_ADMIN]：6 维度规则 + 严重度加权评分 + 任务执行 + 质量报告（结构化 / Word 文档）。
 * <p>标准 6 维度：完整性 COMPLETENESS / 唯一性 UNIQUENESS / 有效性 VALIDITY /
 * 及时性 TIMELINESS / 准确性 ACCURACY / 一致性 CONSISTENCY。
 * <p>历史维度值（中文/旧英文）经 {@link #normalizeDim} 归一到标准 code，修「维度中英文不一致导致假性 PASS」的 bug。
 */
@RestController
@RequestMapping("/api/data-gov/quality")
@CrossOrigin(origins = "*")
public class DataQualityController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    /** 维度 code → 中文 label（顺序即下拉顺序）。 */
    private static final LinkedHashMap<String, String> DIMENSIONS = new LinkedHashMap<>();
    static {
        DIMENSIONS.put("COMPLETENESS", "完整性");
        DIMENSIONS.put("UNIQUENESS", "唯一性");
        DIMENSIONS.put("VALIDITY", "有效性");
        DIMENSIONS.put("TIMELINESS", "及时性");
        DIMENSIONS.put("ACCURACY", "准确性");
        DIMENSIONS.put("CONSISTENCY", "一致性");
    }

    // ===== 维度字典（前端下拉） =====
    @GetMapping("/dimensions")
    public List<Map<String, String>> dimensions() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, String>> out = new ArrayList<>();
        DIMENSIONS.forEach((code, label) -> out.add(Map.of("code", code, "label", label)));
        return out;
    }

    // ===== 规则 =====
    @GetMapping("/rule")
    public List<Map<String, Object>> listRule(@RequestParam(required = false) String dimension) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT id, name, dimension, ds_id, table_name, column_name, expression, threshold, severity, description, status, create_time FROM meta.gov_quality_rule";
        if (dimension == null || dimension.isEmpty())
            return jdbc.queryForList(sql + " ORDER BY id");
        return jdbc.queryForList(sql + " WHERE dimension=? ORDER BY id", dimension);
    }
    @PostMapping("/rule")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_quality_rule(id, name, dimension, ds_id, table_name, column_name, expression, threshold, severity, description, status, create_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), normalizeDim(str(b.get("dimension"))), lng(b.get("ds_id")), str(b.get("table_name")),
                str(b.get("column_name")), str(b.get("expression")), dbl(b.get("threshold")),
                strOrDefault(b.get("severity"), "MAJOR"), str(b.get("description")), strOrDefault(b.get("status"), "ENABLED"), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/rule")
    public Map<String, Object> updateRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_quality_rule SET name=?, dimension=?, ds_id=?, table_name=?, column_name=?, expression=?, threshold=?, severity=?, description=?, status=? WHERE id=?",
                str(b.get("name")), normalizeDim(str(b.get("dimension"))), lng(b.get("ds_id")), str(b.get("table_name")),
                str(b.get("column_name")), str(b.get("expression")), dbl(b.get("threshold")),
                strOrDefault(b.get("severity"), "MAJOR"), str(b.get("description")), strOrDefault(b.get("status"), "ENABLED"), lng(b.get("id")));
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
                id, str(b.get("name")), str(b.get("rule_ids")), str(b.get("cron")), strOrDefault(b.get("status"), "ENABLED"), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @DeleteMapping("/task")
    public Map<String, Object> deleteTask(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_quality_task WHERE id=?", id);
        return Map.of("success", true);
    }

    /**
     * 执行任务下所有规则：逐规则算 metric/score/状态 → 聚合总分/等级/维度·表摘要 → 落 gov_quality_report。
     * 返回体含历史兼容字段 total/pass/fail 与本次报告摘要。
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> task = jdbc.queryForMap("SELECT id, name, rule_ids FROM meta.gov_quality_task WHERE id=?", taskId);
        List<Long> ruleIds = parseIds(str(task.get("rule_ids")));
        int pass = 0, fail = 0, error = 0;
        for (Long rid : ruleIds) {
            try {
                if (runRule(taskId, rid)) pass++; else fail++;
            } catch (Exception e) {
                error++;
                record(taskId, rid, "ERROR", 0, 0, 0, 0, 0, "", "", rootMsg(e));
            }
        }
        Map<String, Object> report = aggregateAndSaveReport(taskId, str(task.get("name")), ruleIds.size(), pass, fail, error);
        Map<String, Object> out = new LinkedHashMap<>(report);
        out.put("success", true);
        out.put("total", ruleIds.size());
        out.put("pass", pass);
        out.put("fail", fail);
        out.put("error", error);
        return out;
    }

    /**
     * 单规则执行：按维度算 metric∈[0,1]（1=满分），score=round(metric*100)，并据阈值判定 PASS/FAIL。
     * <ul>
     *   <li>COMPLETENESS：空率 = COUNT(col IS NULL)/total；threshold=最大允许空率；metric=1-空率</li>
     *   <li>UNIQUENESS：唯一率 = COUNT(DISTINCT col)/total；threshold=最低唯一率；metric=唯一率</li>
     *   <li>TIMELINESS：最新数据距今小时 = TIMESTAMPDIFF(HOUR, MAX(col), NOW())；threshold=最大允许延迟小时</li>
     *   <li>VALIDITY/ACCURACY/CONSISTENCY：expression 当 WHERE 统计违规数；threshold=最大违规数；metric=1-违规率</li>
     * </ul>
     */
    private boolean runRule(long taskId, long ruleId) throws Exception {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT dimension, ds_id, table_name, column_name, expression, threshold, severity FROM meta.gov_quality_rule WHERE id=?", ruleId);
        String dim = normalizeDim(str(r.get("dimension")));
        String table = str(r.get("table_name"));
        String col = str(r.get("column_name"));
        String sev = strOrDefault(r.get("severity"), "MAJOR");
        double threshold = dbl(r.get("threshold"));
        DataSourceDescriptor ds = loader.load(lng(r.get("ds_id")));
        registry.adapter(ds.type);   // fail-fast：适配器不存在即在此抛错（被上层捕获记 ERROR）
        DataSource pool = registry.getPool(ds);

        long total = qLong(pool, "SELECT COUNT(*) FROM " + table);
        double value;
        long violate;
        double metric;
        boolean ok;
        switch (dim) {
            case "COMPLETENESS" -> {
                long nulls = qLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + col + " IS NULL");
                violate = nulls;
                value = total == 0 ? 0 : (double) nulls / total;
                ok = value <= threshold;
                metric = total == 0 ? 1 : 1 - value;
            }
            case "UNIQUENESS" -> {
                long distinct = qLong(pool, "SELECT COUNT(DISTINCT " + col + ") FROM " + table);
                violate = total - distinct;
                value = total == 0 ? 1 : (double) distinct / total;
                ok = value >= threshold;
                metric = value;
            }
            case "TIMELINESS" -> {
                double hours = qDouble(pool, "SELECT TIMESTAMPDIFF(HOUR, MAX(" + col + "), NOW()) FROM " + table);
                value = hours;
                violate = hours <= threshold ? 0 : 1;
                ok = hours <= threshold;
                metric = ok ? 1 : Math.max(0, 1 - (hours - threshold) / Math.max(threshold, 1));
            }
            default -> {   // VALIDITY / ACCURACY / CONSISTENCY
                String expr = str(r.get("expression"));
                violate = expr.isEmpty() ? 0 : qLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + expr);
                value = violate;
                ok = violate <= threshold;
                metric = total == 0 ? 1 : 1 - (double) violate / total;
            }
        }
        int score = (int) Math.round(metric * 100);
        record(taskId, ruleId, ok ? "PASS" : "FAIL", value, threshold, violate, total, score, table, sev, "");
        return ok;
    }

    private void record(long taskId, long ruleId, String status, double value, double threshold,
                        long violate, long total, int score, String tableName, String severity, String err) {
        jdbc.update("INSERT INTO meta.gov_quality_result(id, task_id, rule_id, status, value, threshold, violate_count, total_count, score, table_name, severity, error_msg, run_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                System.currentTimeMillis() + (long) (Math.random() * 1000),
                taskId, ruleId, status, value, threshold, violate, total, score, tableName, severity, err, new Timestamp(System.currentTimeMillis()));
    }

    /** 聚合本次 run 结果：加权总分 = Σ(score*weight)/Σweight；落 gov_quality_report；返回报告摘要。 */
    private Map<String, Object> aggregateAndSaveReport(long taskId, String taskName, int totalRules, int pass, int fail, int error) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT res.status, res.score, res.severity, res.table_name, r.dimension " +
                        "FROM meta.gov_quality_result res LEFT JOIN meta.gov_quality_rule r ON r.id=res.rule_id " +
                        "WHERE res.task_id=? ORDER BY res.id DESC LIMIT " + Math.max(totalRules, 1), taskId);
        double wsum = 0, wtot = 0;
        boolean blockerFail = false;
        Map<String, double[]> dim = new LinkedHashMap<>();   // [wsum, wtot, pass, fail]
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

        long reportId = System.currentTimeMillis();
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

    private void acc(Map<String, double[]> m, String key, int score, int w, String status) {
        double[] a = m.computeIfAbsent(key, k -> new double[4]);
        a[0] += score * (double) w; a[1] += w;
        if ("PASS".equals(status)) a[2]++; else if ("FAIL".equals(status) || "ERROR".equals(status)) a[3]++;
    }
    private static String gradeOf(int overall, boolean blockerFail) {
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

    // ===== 报告 =====

    /** 最新报告：任务信息 + latest(最新快照) + history(趋势) + ruleResults(最新一批明细)。 */
    @GetMapping("/report")
    public Map<String, Object> report(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> task = jdbc.queryForMap("SELECT id, name FROM meta.gov_quality_task WHERE id=?", taskId);
        List<Map<String, Object>> reports = jdbc.queryForList(
                "SELECT id, run_time, overall_score, grade, total_rules, pass_count, fail_count, error_count, dim_summary, table_summary " +
                        "FROM meta.gov_quality_report WHERE task_id=? ORDER BY id DESC", taskId);
        int n = reports.isEmpty() ? 50 : ((Number) reports.get(0).get("total_rules")).intValue();
        List<Map<String, Object>> ruleResults = jdbc.queryForList(
                "SELECT res.id, res.rule_id, r.name AS rule_name, r.dimension, res.status, res.value, res.threshold, " +
                        "res.violate_count, res.total_count, res.score, res.severity, res.table_name, res.error_msg, res.run_time " +
                        "FROM meta.gov_quality_result res LEFT JOIN meta.gov_quality_rule r ON r.id=res.rule_id " +
                        "WHERE res.task_id=? ORDER BY res.id DESC LIMIT " + Math.max(n, 1), taskId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("taskName", str(task.get("name")));
        out.put("latest", reports.isEmpty() ? null : reports.get(0));
        out.put("history", reports);
        out.put("ruleResults", ruleResults);
        return out;
    }

    @GetMapping("/report/list")
    public List<Map<String, Object>> reportList(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, task_id, run_time, overall_score, grade, total_rules, pass_count, fail_count, error_count FROM meta.gov_quality_report WHERE task_id=? ORDER BY id DESC", taskId);
    }

    /** Word(.docx) 质量报告：Apache POI XWPF 生成，含标题/概览/维度·表得分/规则明细表格。 */
    @GetMapping("/report/word")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> reportWord(@RequestParam long taskId) throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> rpt = report(taskId);
        Map<String, Object> latest = (Map<String, Object>) rpt.get("latest");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) rpt.get("ruleResults");
        String taskName = str(rpt.get("taskName"));
        int overall = latest == null ? 0 : ((Number) latest.get("overall_score")).intValue();
        String grade = latest == null ? "N/A" : str(latest.get("grade"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            // 标题
            para(doc, "数据质量报告", 22, true, "1F3864", false);
            para(doc, "任务：" + taskName + "    生成时间：" + (latest == null ? "-" : str(latest.get("run_time"))), 10, false, "808080", false);
            para(doc, "综合质量分：" + overall + " / 100        质量等级：" + grade, 16, true, scoreHex(overall), true);
            if (latest != null && ((Number) latest.get("error_count")).intValue() > 0)
                para(doc, "提示：本次执行包含 " + latest.get("error_count") + " 条异常规则（已按 0 分计入总分）", 10, false, "C00000", false);

            // 一、执行概览
            para(doc, "一、执行概览", 14, true, "2F6BFF", true);
            XWPFTable ov = newTable(doc, new String[]{"规则总数", "通过", "失败", "异常"});
            XWPFTableRow ovRow = ov.createRow();
            cellText(ovRow.getCell(0), String.valueOf(latest == null ? 0 : ((Number) latest.get("total_rules")).intValue()), false, "000000");
            cellText(ovRow.getCell(1), String.valueOf(latest == null ? 0 : ((Number) latest.get("pass_count")).intValue()), true, "18B566");
            cellText(ovRow.getCell(2), String.valueOf(latest == null ? 0 : ((Number) latest.get("fail_count")).intValue()), true, "E54D4D");
            cellText(ovRow.getCell(3), String.valueOf(latest == null ? 0 : ((Number) latest.get("error_count")).intValue()), true, "F5A623");

            // 二、维度得分
            para(doc, "二、维度得分", 14, true, "2F6BFF", true);
            XWPFTable dt = newTable(doc, new String[]{"维度", "得分", "规则数", "通过", "失败"});
            fillSummaryTable(dt, latest == null ? "" : str(latest.get("dim_summary")), true);

            // 三、各表得分
            para(doc, "三、各表得分", 14, true, "2F6BFF", true);
            XWPFTable tt = newTable(doc, new String[]{"数据表", "得分", "规则数", "通过", "失败"});
            fillSummaryTable(tt, latest == null ? "" : str(latest.get("table_summary")), false);

            // 四、规则明细
            para(doc, "四、规则明细", 14, true, "2F6BFF", true);
            XWPFTable rt = newTable(doc, new String[]{"规则", "维度", "严重度", "状态", "实际值", "阈值", "违规/总数", "得分"});
            if (rules.isEmpty()) {
                XWPFTableRow er = rt.createRow();
                cellText(er.getCell(0), "尚无执行结果", false, "808080");
            }
            for (Map<String, Object> row : rules) {
                String status = str(row.get("status"));
                int sc = row.get("score") == null ? 0 : ((Number) row.get("score")).intValue();
                XWPFTableRow rr = rt.createRow();
                cellText(rr.getCell(0), str(row.get("rule_name")), false, "000000");
                cellText(rr.getCell(1), DIMENSIONS.getOrDefault(normalizeDim(str(row.get("dimension"))), str(row.get("dimension"))), false, "000000");
                cellText(rr.getCell(2), sevLabel(str(row.get("severity"))), false, "000000");
                cellText(rr.getCell(3), status, true, "PASS".equals(status) ? "18B566" : "E54D4D");
                cellText(rr.getCell(4), fmt(row.get("value")), false, "000000");
                cellText(rr.getCell(5), fmt(row.get("threshold")), false, "000000");
                cellText(rr.getCell(6), str(row.get("violate_count")) + " / " + str(row.get("total_count")), false, "000000");
                cellText(rr.getCell(7), String.valueOf(sc), true, scoreHex(sc));
            }
            para(doc, "由 kaidata 数据质量模块自动生成", 9, false, "A6A6A6", true);
            doc.write(out);
        }
        byte[] bytes = out.toByteArray();
        String filename = "质量报告_" + taskName + ".docx";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    // ===== Word 生成辅助 =====

    private void para(XWPFDocument doc, String text, int size, boolean bold, String color, boolean spaceBefore) {
        XWPFParagraph p = doc.createParagraph();
        if (spaceBefore) p.setSpacingBefore(160);
        XWPFRun r = p.createRun();
        r.setText(text);
        setRun(r, "微软雅黑", size, bold, color);
    }

    /** 建表并填充表头（蓝底白字）。 */
    private XWPFTable newTable(XWPFDocument doc, String[] headers) {
        XWPFTable t = doc.createTable(1, headers.length);
        try { t.setWidth(9360); } catch (Exception ignored) {}
        XWPFTableRow hr = t.getRow(0);
        for (int i = 0; i < headers.length; i++) { hr.getCell(i).setColor("2F6BFF"); cellText(hr.getCell(i), headers[i], true, "FFFFFF"); }
        return t;
    }

    private void cellText(XWPFTableCell cell, String text, boolean bold, String color) {
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        for (int i = p.getRuns().size() - 1; i >= 0; i--) p.removeRun(i);
        XWPFRun r = p.createRun();
        r.setText(text == null ? "" : text);
        setRun(r, "微软雅黑", 10, bold, color);
    }

    /** 维度/表摘要 JSON → 表格数据行。isDim=true 时 name 走维度 label 映射。 */
    @SuppressWarnings("unchecked")
    private void fillSummaryTable(XWPFTable t, String summaryJson, boolean isDim) {
        if (summaryJson == null || summaryJson.isBlank()) return;
        try {
            Map<String, Object> root = json.readValue(summaryJson, Map.class);
            for (Map.Entry<String, Object> e : root.entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                int sc = ((Number) v.get("score")).intValue();
                String name = isDim ? DIMENSIONS.getOrDefault(e.getKey(), e.getKey()) : e.getKey();
                XWPFTableRow row = t.createRow();
                cellText(row.getCell(0), name, false, "000000");
                cellText(row.getCell(1), String.valueOf(sc), true, scoreHex(sc));
                cellText(row.getCell(2), String.valueOf(v.get("rules")), false, "000000");
                cellText(row.getCell(3), String.valueOf(v.get("pass")), false, "000000");
                cellText(row.getCell(4), String.valueOf(v.get("fail")), false, "000000");
            }
        } catch (Exception ignored) {}
    }

    /** run 字体 + 字号 + 粗细 + 颜色（中文走 Word 默认 CJK 字体）。 */
    private static void setRun(XWPFRun r, String font, int size, boolean bold, String color) {
        r.setFontFamily(font);
        r.setFontSize(size);
        r.setBold(bold);
        if (color != null) r.setColor(color);
    }

    private static String scoreHex(int p) { return p >= 90 ? "18B566" : p >= 80 ? "2F6BFF" : p >= 60 ? "F5A623" : "E54D4D"; }
    private static String sevLabel(String s) {
        return switch (s) { case "BLOCKER" -> "阻断"; case "CRITICAL" -> "严重"; case "MINOR" -> "次要"; default -> "主要"; };
    }
    private static String fmt(Object o) {
        if (o == null) return "";
        if (o instanceof Number) { double d = ((Number) o).doubleValue(); return (d % 1 == 0) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 1000) / 1000.0); }
        return String.valueOf(o);
    }

    // ===== 历史结果列表（兼容旧前端结果弹窗） =====
    @GetMapping("/result")
    public List<Map<String, Object>> result(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT res.id, res.task_id, res.rule_id, r.name AS rule_name, r.dimension, res.status, " +
                "res.value, res.threshold, res.violate_count, res.total_count, res.score, res.severity, res.error_msg, res.run_time " +
                "FROM meta.gov_quality_result res LEFT JOIN meta.gov_quality_rule r ON r.id=res.rule_id " +
                "WHERE res.task_id=? ORDER BY res.id DESC", taskId);
    }

    // -------- 助手 --------
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
    private static String normalizeDim(String raw) {
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
