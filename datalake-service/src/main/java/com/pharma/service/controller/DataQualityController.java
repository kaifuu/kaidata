package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.quality.QualityExecutor;
import com.pharma.service.access.quality.QualityScheduler;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.security.AuthContext;
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

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.sql.Timestamp;
import java.util.*;

/**
 * 数据质量 [SYS_ADMIN]：6 维度规则 + 严重度加权评分 + 任务执行 + 质量报告（结构化 / Word 文档）。
 * <p>标准 6 维度：完整性 COMPLETENESS / 唯一性 UNIQUENESS / 有效性 VALIDITY /
 * 及时性 TIMELINESS / 准确性 ACCURACY / 一致性 CONSISTENCY。
 * <p>历史维度值（中文/旧英文）经 {@link #normalizeDim} 归一到标准 code，修「维度中英文不一致导致假性 PASS」的 bug。
 * <p>P0 升级：规则 ident 校验（表/列防注入）+ sample_rows 采样 + /rule/dry-run 试运行 +
 * 问题工单闭环（/issue/list·assign·resolve·sample-csv）。
 */
@RestController
@RequestMapping("/api/data-gov/quality")
@CrossOrigin(origins = "*")
public class DataQualityController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private QualityExecutor qualityExecutor;
    @Autowired private QualityScheduler qualityScheduler;
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
        String sql = "SELECT id, name, dimension, ds_id, table_name, column_name, expression, threshold, severity, description, status, sample_rows, create_time FROM meta.gov_quality_rule";
        if (dimension == null || dimension.isEmpty())
            return jdbc.queryForList(sql + " ORDER BY id");
        return jdbc.queryForList(sql + " WHERE dimension=? ORDER BY id", dimension);
    }
    @PostMapping("/rule")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        validateIdents(str(b.get("table_name")), str(b.get("column_name")));
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.gov_quality_rule(id, name, dimension, ds_id, table_name, column_name, expression, threshold, severity, description, status, sample_rows, create_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), normalizeDim(str(b.get("dimension"))), lng(b.get("ds_id")), str(b.get("table_name")),
                str(b.get("column_name")), str(b.get("expression")), dbl(b.get("threshold")),
                strOrDefault(b.get("severity"), "MAJOR"), str(b.get("description")), strOrDefault(b.get("status"), "ENABLED"),
                lng(b.get("sample_rows")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping("/rule")
    public Map<String, Object> updateRule(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        validateIdents(str(b.get("table_name")), str(b.get("column_name")));
        jdbc.update("UPDATE meta.gov_quality_rule SET name=?, dimension=?, ds_id=?, table_name=?, column_name=?, expression=?, threshold=?, severity=?, description=?, status=?, sample_rows=? WHERE id=?",
                str(b.get("name")), normalizeDim(str(b.get("dimension"))), lng(b.get("ds_id")), str(b.get("table_name")),
                str(b.get("column_name")), str(b.get("expression")), dbl(b.get("threshold")),
                strOrDefault(b.get("severity"), "MAJOR"), str(b.get("description")), strOrDefault(b.get("status"), "ENABLED"),
                lng(b.get("sample_rows")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping("/rule")
    public Map<String, Object> deleteRule(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_quality_rule WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 试运行：按请求体规则（不必先保存）执行一次，返回指标 + 违规行样例；不落结果不动工单。 */
    @PostMapping("/rule/dry-run")
    public Map<String, Object> dryRun(@RequestBody Map<String, Object> b) throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        validateIdents(str(b.get("table_name")), str(b.get("column_name")));
        return qualityExecutor.dryRun(b);
    }

    /** 表名/列名 ident 校验（防注入；与落标通道同一套规则）。表名按 '.' 分段至多两段。 */
    private static void validateIdents(String table, String col) {
        if (table == null || table.isEmpty()) throw new IllegalArgumentException("table_name 不能为空");
        String[] segs = table.split("\\.", -1);
        if (segs.length > 2) throw new IllegalArgumentException("非法表名: " + table);
        for (String s : segs) StarRocksDdlBuilder.ident(s);
        if (col != null && !col.isEmpty()) StarRocksDdlBuilder.ident(col);
    }

    // ===== 问题工单（派单闭环：OPEN→ASSIGNED→PROCESSING→RESOLVED→CLOSED，可驳回/重开，全程流转日志） =====

    /** 工单列表：status 空=全部；keyword 模糊匹配 表/规则/处理人；overdue=1 只看超期（未结且 deadline 已过）。 */
    @GetMapping("/issue/list")
    public List<Map<String, Object>> issueList(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) Long taskId,
                                               @RequestParam(required = false) String severity,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false, defaultValue = "0") int overdue) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder(
                "SELECT i.id, i.task_id, i.rule_id, i.table_name, i.dimension, i.severity, i.status, i.assignee, i.deadline, " +
                "i.violate_count, i.sample_json, i.create_time, i.resolve_time, i.resolve_comment, " +
                "(CASE WHEN i.deadline IS NOT NULL AND i.deadline < NOW() AND i.status IN ('OPEN','ASSIGNED','PROCESSING') THEN 1 ELSE 0 END) AS overdue, " +
                "r.name AS rule_name, r.expression, t.name AS task_name " +
                "FROM meta.gov_quality_issue i " +
                "LEFT JOIN meta.gov_quality_rule r ON r.id=i.rule_id " +
                "LEFT JOIN meta.gov_quality_task t ON t.id=i.task_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) { sql.append(" AND i.status=?"); args.add(status); }
        if (taskId != null && taskId > 0) { sql.append(" AND i.task_id=?"); args.add(taskId); }
        if (severity != null && !severity.isEmpty()) { sql.append(" AND i.severity=?"); args.add(severity); }
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (i.table_name LIKE ? OR r.name LIKE ? OR i.assignee LIKE ?)");
            String like = "%" + keyword + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (overdue == 1) sql.append(" AND i.deadline IS NOT NULL AND i.deadline < NOW() AND i.status IN ('OPEN','ASSIGNED','PROCESSING')");
        sql.append(" ORDER BY i.id DESC LIMIT 300");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 工单统计：各状态数 + 超期数 + 按严重度/维度分布（工单中心顶部卡片）。 */
    @GetMapping("/issue/stats")
    public Map<String, Object> issueStats() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue"));
        out.put("open", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue WHERE status='OPEN'"));
        out.put("assigned", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue WHERE status='ASSIGNED'"));
        out.put("processing", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue WHERE status='PROCESSING'"));
        out.put("resolved", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue WHERE status='RESOLVED'"));
        out.put("closed", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue WHERE status='CLOSED'"));
        out.put("overdue", cnt("SELECT COUNT(*) FROM meta.gov_quality_issue WHERE deadline IS NOT NULL AND deadline < NOW() AND status IN ('OPEN','ASSIGNED','PROCESSING')"));
        out.put("bySeverity", jdbc.queryForList("SELECT severity, COUNT(*) cnt FROM meta.gov_quality_issue GROUP BY severity"));
        out.put("byDimension", jdbc.queryForList("SELECT dimension, COUNT(*) cnt FROM meta.gov_quality_issue GROUP BY dimension"));
        return out;
    }

    /** 可派单人员：启用状态的系统用户（派单下拉）。 */
    @GetMapping("/issue/users")
    public List<Map<String, Object>> issueUsers() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT username, name FROM meta.sys_user WHERE status IN ('NORMAL','ENABLED','启用') ORDER BY username LIMIT 200");
    }

    /** 流转日志（工单详情时间线）。 */
    @GetMapping("/issue/log")
    public List<Map<String, Object>> issueLog(@RequestParam long issueId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, action, operator, comment, create_time FROM meta.gov_quality_issue_log WHERE issue_id=? ORDER BY id", issueId);
    }

    /** 派单：OPEN（或驳回后重开）→ ASSIGNED，记处理人与期望完成时间。 */
    @PostMapping("/issue/assign")
    public Map<String, Object> issueAssign(@RequestParam long id, @RequestParam String assignee,
                                           @RequestParam(required = false) String deadline,
                                           @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = jdbc.update("UPDATE meta.gov_quality_issue SET assignee=?, status='ASSIGNED', deadline=? WHERE id=? AND status IN ('OPEN','ASSIGNED')",
                assignee, parseTs(deadline), id);
        if (n == 0) return Map.of("success", false, "msg", "仅待派单/已派单状态可派单");
        issueLog(id, "ASSIGN", "指派给 " + assignee + (comment.isEmpty() ? "" : "：" + comment));
        return Map.of("success", true);
    }

    /** 批量派单：一次把多条待派单工单派给同一人。 */
    @PostMapping("/issue/batch-assign")
    public Map<String, Object> issueBatchAssign(@RequestParam String ids, @RequestParam String assignee,
                                                @RequestParam(required = false) String deadline,
                                                @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = 0;
        for (String s : ids.split(",")) {
            long id = Long.parseLong(s.trim());
            int k = jdbc.update("UPDATE meta.gov_quality_issue SET assignee=?, status='ASSIGNED', deadline=? WHERE id=? AND status IN ('OPEN','ASSIGNED')",
                    assignee, parseTs(deadline), id);
            if (k > 0) { issueLog(id, "ASSIGN", "批量指派给 " + assignee + (comment.isEmpty() ? "" : "：" + comment)); n += k; }
        }
        return Map.of("success", true, "count", n);
    }

    /** 开始处理：ASSIGNED → PROCESSING。 */
    @PostMapping("/issue/process")
    public Map<String, Object> issueProcess(@RequestParam long id, @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = jdbc.update("UPDATE meta.gov_quality_issue SET status='PROCESSING' WHERE id=? AND status IN ('ASSIGNED','PROCESSING')", id);
        if (n == 0) return Map.of("success", false, "msg", "仅已派单状态可开始处理");
        issueLog(id, "PROCESS", comment.isEmpty() ? "开始处理" : comment);
        return Map.of("success", true);
    }

    /** 解决：PROCESSING/ASSIGNED → RESOLVED（记解决说明与时间）。 */
    @PostMapping("/issue/resolve")
    public Map<String, Object> issueResolve(@RequestParam long id, @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = jdbc.update("UPDATE meta.gov_quality_issue SET status='RESOLVED', resolve_time=?, resolve_comment=? WHERE id=? AND status IN ('OPEN','ASSIGNED','PROCESSING')",
                new Timestamp(System.currentTimeMillis()), comment, id);
        if (n == 0) return Map.of("success", false, "msg", "该工单已解决或关闭");
        issueLog(id, "RESOLVE", comment.isEmpty() ? "标记已解决" : comment);
        return Map.of("success", true);
    }

    /** 关闭（验收）：RESOLVED → CLOSED。 */
    @PostMapping("/issue/close")
    public Map<String, Object> issueClose(@RequestParam long id, @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = jdbc.update("UPDATE meta.gov_quality_issue SET status='CLOSED' WHERE id=? AND status='RESOLVED'", id);
        if (n == 0) return Map.of("success", false, "msg", "仅已解决状态可验收关闭");
        issueLog(id, "CLOSE", comment.isEmpty() ? "验收关闭" : comment);
        return Map.of("success", true);
    }

    /** 驳回：ASSIGNED/PROCESSING → OPEN（处理人无法解决，退回重派），清空处理人。 */
    @PostMapping("/issue/reject")
    public Map<String, Object> issueReject(@RequestParam long id, @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = jdbc.update("UPDATE meta.gov_quality_issue SET status='OPEN', assignee='' WHERE id=? AND status IN ('ASSIGNED','PROCESSING')", id);
        if (n == 0) return Map.of("success", false, "msg", "仅已派单/处理中状态可驳回");
        issueLog(id, "REJECT", comment.isEmpty() ? "驳回重派" : comment);
        return Map.of("success", true);
    }

    /** 重开：RESOLVED/CLOSED → OPEN（问题复现）。 */
    @PostMapping("/issue/reopen")
    public Map<String, Object> issueReopen(@RequestParam long id, @RequestParam(required = false, defaultValue = "") String comment) {
        Authz.require(Authz.SYS_ADMIN);
        int n = jdbc.update("UPDATE meta.gov_quality_issue SET status='OPEN', assignee='', resolve_time=NULL, resolve_comment='' WHERE id=? AND status IN ('RESOLVED','CLOSED')", id);
        if (n == 0) return Map.of("success", false, "msg", "仅已解决/已关闭状态可重开");
        issueLog(id, "REOPEN", comment.isEmpty() ? "问题复现，重开工单" : comment);
        return Map.of("success", true);
    }

    /** 写流转日志（operator 取当前登录人）。id 用 MAX(id)+1 保证同毫秒多条也严格保序。 */
    private void issueLog(long issueId, String action, String comment) {
        try {
            Long max = jdbc.queryForObject("SELECT MAX(id) FROM meta.gov_quality_issue_log", Long.class);
            jdbc.update("INSERT INTO meta.gov_quality_issue_log(id, issue_id, action, operator, comment, create_time) VALUES (?,?,?,?,?,?)",
                    (max == null ? 0 : max) + 1, issueId, action, AuthContext.username(), comment, new Timestamp(System.currentTimeMillis()));
        } catch (Exception ignored) {}
    }

    /** "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-ddTHH:mm" → Timestamp；空/非法返回 null。 */
    private static Timestamp parseTs(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Timestamp.valueOf(s.replace('T', ' ').length() == 16 ? s.replace('T', ' ') + ":00" : s.replace('T', ' ')); }
        catch (Exception e) { return null; }
    }

    private long cnt(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    /** 问题数据明细 CSV：sample_json 平铺导出（UTF-8 BOM，Excel 直开）。 */
    @GetMapping("/issue/sample-csv")
    public ResponseEntity<byte[]> issueSampleCsv(@RequestParam long id) throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT sample_json, table_name FROM meta.gov_quality_issue WHERE id=?", id);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        List<Map<String, Object>> sample = List.of();
        try { @SuppressWarnings("unchecked") List<Map<String, Object>> s = json.readValue(str(rows.get(0).get("sample_json")), List.class); sample = s; } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder("﻿");
        if (!sample.isEmpty()) {
            List<String> cols = new ArrayList<>(sample.get(0).keySet());
            sb.append(String.join(",", cols.stream().map(DataQualityController::csvCell).toList())).append("\r\n");
            for (Map<String, Object> r : sample) {
                sb.append(cols.stream().map(c -> csvCell(str(r.get(c)))).reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
            }
        }
        String filename = "问题数据_" + str(rows.get(0).get("table_name")) + "_" + id + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(sb.toString().getBytes("UTF-8"));
    }
    private static String csvCell(Object v) {
        String s = v == null ? "" : String.valueOf(v).replace("\r", " ").replace("\n", " ");
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
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
        qualityScheduler.stop(id);
        jdbc.update("DELETE FROM meta.gov_quality_task WHERE id=?", id);
        return Map.of("success", true);
    }
    /** 上线：ENABLED + 加入周期调度（质量检测串入流水线）。 */
    @PostMapping("/task/online")
    public Map<String, Object> onlineTask(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> t = jdbc.queryForMap("SELECT cron FROM meta.gov_quality_task WHERE id=?", id);
        jdbc.update("UPDATE meta.gov_quality_task SET status='ENABLED' WHERE id=?", id);
        qualityScheduler.start(id, str(t.get("cron")));
        return Map.of("success", true);
    }
    /** 下线：DISABLED + 停调度。 */
    @PostMapping("/task/offline")
    public Map<String, Object> offlineTask(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.gov_quality_task SET status='DISABLED' WHERE id=?", id);
        qualityScheduler.stop(id);
        return Map.of("success", true);
    }

    /**
     * 执行任务下所有规则：逐规则算 metric/score/状态 → 聚合总分/等级/维度·表摘要 → 落 gov_quality_report。
     * FAIL 规则自动生成/刷新问题工单（含违规行样例），复检 PASS 自动核销。
     */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long taskId) {
        Authz.require(Authz.SYS_ADMIN);
        return qualityExecutor.run(taskId);
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
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String strOrDefault(Object o, String def) { String s = str(o); return s.isEmpty() ? def : s; }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static double dbl(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).doubleValue(); try { return Double.parseDouble(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
