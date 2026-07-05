package com.pharma.service.access.develop;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工作流执行器（任务链）：按节点 sort 顺序执行 script/export 节点，落执行历史 + log。
 * 含周期调度（cron 秒数，ONLINE 时启动）。
 */
@Component
public class DevWorkflowExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor scriptExecutor;
    @Autowired private DevExportExecutor exportExecutor;

    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "dev-workflow"); t.setDaemon(true); return t;
    });
    private final Map<Long, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public Map<String, Object> run(long workflowId) {
        long runId = System.currentTimeMillis();
        Timestamp now = new Timestamp(runId);
        StringBuilder log = new StringBuilder("工作流 ").append(workflowId).append(" 开始 ").append(now).append("\n");
        String status = "SUCCESS";
        int total = 0, pass = 0;
        try {
            List<Map<String, Object>> nodes = jdbc.queryForList(
                    "SELECT node_type, node_id FROM meta.dev_workflow_node WHERE workflow_id=? ORDER BY sort, id", workflowId);
            total = nodes.size();
            log.append("节点数: ").append(total).append("\n");
            for (Map<String, Object> n : nodes) {
                String nt = String.valueOf(n.get("node_type"));
                long nid = ((Number) n.get("node_id")).longValue();
                log.append("  [").append(nt).append(":").append(nid).append("] ");
                try {
                    if ("script".equals(nt)) {
                        Map<String, Object> r = scriptExecutor.runScript(nid);
                        log.append("status=").append(r.get("status")).append(", rowsRead=").append(r.get("rowsRead")).append("\n");
                        if (!"FAIL".equals(r.get("status"))) pass++;
                    } else if ("export".equals(nt)) {
                        Map<String, Object> r = exportExecutor.run(nid);
                        log.append("status=").append(r.get("status")).append(", rowsOut=").append(r.get("rowsOut")).append("\n");
                        if (!"FAIL".equals(r.get("status"))) pass++;
                    } else {
                        log.append("暂不支持的节点类型: ").append(nt).append("\n");
                    }
                } catch (Exception e) {
                    log.append("失败: ").append(rootMsg(e)).append("\n");
                }
            }
            log.append("完成: ").append(pass).append("/").append(total).append(" 成功\n");
        } catch (Exception e) {
            status = "FAIL";
            log.append("工作流失败: ").append(rootMsg(e)).append("\n");
        }
        String logText = log.length() > 65000 ? log.substring(0, 65000) : log.toString();
        jdbc.update("INSERT INTO meta.dev_workflow_run(id, workflow_id, status, nodes_total, nodes_pass, log_text, run_time) VALUES (?,?,?,?,?,?,?)",
                runId, workflowId, status, total, pass, logText, new Timestamp(System.currentTimeMillis()));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", status);
        out.put("nodesTotal", total);
        out.put("nodesPass", pass);
        return out;
    }

    public void start(long workflowId, String cronSec) {
        stop(workflowId);
        int sec = Math.max(parseSec(cronSec), 30);
        ScheduledFuture<?> f = pool.scheduleAtFixedRate(() -> safeRun(workflowId), sec, sec, TimeUnit.SECONDS);
        tasks.put(workflowId, f);
    }
    public void stop(long workflowId) {
        ScheduledFuture<?> f = tasks.remove(workflowId);
        if (f != null) f.cancel(false);
    }
    private void safeRun(long id) { try { run(id); } catch (Exception e) { System.err.println("[DevWorkflow] " + id + " 周期失败: " + e.getMessage()); } }
    private static int parseSec(String s) { if (s == null || s.isBlank()) return 300; try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 300; } }
    @PreDestroy public void shutdown() { pool.shutdownNow(); }
    private static String rootMsg(Throwable e) { Throwable c = e; for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause(); String m = c.getMessage(); return m == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + m; }
}
