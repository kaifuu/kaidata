package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 任务日志 [SYS_ADMIN]：聚合脚本/离线/接出/工作流四源执行日志，统一查询/查看/清除。
 */
@RestController
@RequestMapping("/api/data-dev/tasklog")
@CrossOrigin(origins = "*")
public class DevTaskLogController {

    @Autowired private JdbcTemplate jdbc;

    private static final Set<String> TYPES = Set.of("SCRIPT", "OFFLINE", "EXPORT", "WORKFLOW");

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) String logType,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String kw,
                                          @RequestParam(required = false) String start,
                                          @RequestParam(required = false) String end,
                                          @RequestParam(defaultValue = "200") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        String union = "(SELECT 'SCRIPT' log_type, r.id run_id, r.script_id source_id, s.name source_name, r.run_time start_time, r.status, r.rows_read num, r.error_msg " +
                "FROM meta.dev_script_run r LEFT JOIN meta.dev_script s ON s.id=r.script_id " +
                "UNION ALL SELECT 'OFFLINE', r.id, r.task_id, t.name, r.start_time, r.status, r.rows_read, r.error_msg " +
                "FROM meta.dev_offline_run r LEFT JOIN meta.dev_offline_task t ON t.id=r.task_id " +
                "UNION ALL SELECT 'EXPORT', r.id, r.export_id, e.name, r.run_time, r.status, r.rows_out, r.error_msg " +
                "FROM meta.dev_export_run r LEFT JOIN meta.dev_export e ON e.id=r.export_id " +
                "UNION ALL SELECT 'WORKFLOW', r.id, r.workflow_id, w.name, r.run_time, r.status, r.nodes_pass, '' " +
                "FROM meta.dev_workflow_run r LEFT JOIN meta.dev_workflow w ON w.id=r.workflow_id) x";
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(union).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (logType != null && !logType.isEmpty()) {
            StringBuilder inList = new StringBuilder();
            for (String t : logType.split(",")) {
                String tt = t.trim().toUpperCase();
                if (TYPES.contains(tt)) inList.append("'").append(tt).append("',");
            }
            if (inList.length() > 0) { inList.setLength(inList.length() - 1); sql.append(" AND log_type IN (").append(inList).append(")"); }
        }
        if (status != null && !status.isEmpty()) { sql.append(" AND status=?"); args.add(status); }
        if (kw != null && !kw.isEmpty()) { sql.append(" AND source_name LIKE ?"); args.add("%" + kw + "%"); }
        if (start != null && !start.isEmpty()) { sql.append(" AND start_time >= ?").toString(); args.add(start); sql = new StringBuilder(sql.toString()); }
        if (end != null && !end.isEmpty()) { sql.append(" AND start_time <= ?"); args.add(end); }
        sql.append(" ORDER BY start_time DESC LIMIT ").append(Math.min(limit, 500));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam String logType, @RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        switch (logType.toUpperCase()) {
            case "SCRIPT": return jdbc.queryForMap("SELECT id, script_id, status, rows_read, cols, error_msg, log_text, run_time FROM meta.dev_script_run WHERE id=?", id);
            case "OFFLINE": return jdbc.queryForMap("SELECT id, task_id, status, rows_read, cols, error_msg, log_text, start_time, end_time FROM meta.dev_offline_run WHERE id=?", id);
            case "EXPORT": return jdbc.queryForMap("SELECT id, export_id, status, rows_out, error_msg, run_time FROM meta.dev_export_run WHERE id=?", id);
            case "WORKFLOW": return jdbc.queryForMap("SELECT id, workflow_id, status, nodes_total, nodes_pass, log_text, run_time FROM meta.dev_workflow_run WHERE id=?", id);
            default: throw new RuntimeException("未知 logType: " + logType);
        }
    }

    @DeleteMapping("/run")
    public Map<String, Object> deleteOne(@RequestParam String logType, @RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta." + tableOf(logType) + " WHERE id=?", id);
        return Map.of("success", true);
    }

    @DeleteMapping("/clear")
    public Map<String, Object> clear(@RequestParam(required = false) String logType,
                                     @RequestParam(defaultValue = "all") String rule) {
        Authz.require(Authz.SYS_ADMIN);
        String cond = "";
        if ("failed".equalsIgnoreCase(rule)) cond = " AND status<>'SUCCESS'";
        else if ("before7d".equalsIgnoreCase(rule)) cond = " AND run_time < DATE_SUB(NOW(), INTERVAL 7 DAY)";
        int total = 0;
        List<String> tables = (logType == null || logType.isEmpty())
                ? List.of("dev_script_run", "dev_offline_run", "dev_export_run", "dev_workflow_run")
                : List.of(tableOf(logType));
        for (String t : tables) {
            try { total += jdbc.update("DELETE FROM meta." + t + " WHERE 1=1" + cond); } catch (Exception ignored) {}
        }
        return Map.of("success", true, "deleted", total);
    }

    private String tableOf(String logType) {
        switch (logType.toUpperCase()) {
            case "SCRIPT": return "dev_script_run";
            case "OFFLINE": return "dev_offline_run";
            case "EXPORT": return "dev_export_run";
            case "WORKFLOW": return "dev_workflow_run";
            default: throw new RuntimeException("未知 logType: " + logType);
        }
    }
}
