package com.pharma.service.access.develop;

import com.pharma.service.access.develop.job.DevJobExecutor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线任务执行器：查任务 → 按 job_type 分发到 DevJobExecutor → 拼 log → 落 dev_offline_run。
 * <p>供 DevOfflineController（手动）与 DevOfflineScheduler（周期）复用。
 */
@Component
public class DevOfflineExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private List<DevJobExecutor> executors;

    private Map<String, DevJobExecutor> registry;

    @PostConstruct
    void init() {
        registry = new HashMap<>();
        if (executors != null) for (DevJobExecutor e : executors) registry.put(e.jobType(), e);
    }

    public Map<String, Object> run(long taskId) {
        long runId = System.currentTimeMillis();
        long start = runId;
        StringBuilder log = new StringBuilder();
        String status = "SUCCESS", errMsg = "";
        long rowsRead = 0;
        int cols = 0;
        String engineJobId = "";
        try {
            Map<String, Object> t = jdbc.queryForMap(
                    "SELECT name, job_type, datasource_id, sql_content, dag_json, config_json FROM meta.dev_offline_task WHERE id=?", taskId);
            String jobType = str(t.get("job_type"));
            if (jobType.isEmpty()) jobType = "jdbc_sql";
            log.append("离线任务[").append(t.get("name")).append("] 类型=").append(jobType).append("\n");
            DevJobExecutor exec = registry.get(jobType);
            if (exec == null) throw new RuntimeException("不支持的作业类型: " + jobType);
            Map<String, Object> r = exec.execute(taskId, t, log);
            rowsRead = lng(r.get("rowsRead"));
            Object c = r.get("cols");
            if (c instanceof Number) cols = ((Number) c).intValue();
            else if (c instanceof List) cols = ((List<?>) c).size();
            engineJobId = str(r.get("engineJobId"));
        } catch (Exception e) {
            status = "FAIL";
            errMsg = rootMsg(e);
            if (errMsg.length() > 2000) errMsg = errMsg.substring(0, 2000);  // 截断，避免超 error_msg varchar(2048)
            log.append("失败: ").append(errMsg).append("\n");
        }
        log.append("耗时 ").append(System.currentTimeMillis() - start).append("ms");
        String logText = log.length() > 65000 ? log.substring(0, 65000) : log.toString();
        try {
            jdbc.update("INSERT INTO meta.dev_offline_run(id, task_id, start_time, end_time, status, rows_read, cols, error_msg, log_text, triggered_by, engine_job_id) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    runId, taskId, new Timestamp(start), new Timestamp(System.currentTimeMillis()),
                    status, rowsRead, cols, errMsg, logText, currentUser(), engineJobId);
        } catch (Exception ignored) {
            // engine_job_id 列若尚未就绪（迁移未跑），回退到无该列的插入
            jdbc.update("INSERT INTO meta.dev_offline_run(id, task_id, start_time, end_time, status, rows_read, cols, error_msg, log_text, triggered_by) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    runId, taskId, new Timestamp(start), new Timestamp(System.currentTimeMillis()),
                    status, rowsRead, cols, errMsg, logText, currentUser());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", status);
        out.put("rowsRead", rowsRead);
        out.put("engineJobId", engineJobId);
        out.put("msg", errMsg);
        return out;
    }

    private static String rootMsg(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : (c.getClass().getSimpleName() + ": " + m);
    }

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
