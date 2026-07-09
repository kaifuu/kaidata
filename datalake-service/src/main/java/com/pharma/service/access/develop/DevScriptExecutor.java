package com.pharma.service.access.develop;

import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 数据开发脚本执行器：按 script_type 分派。
 * <ul>
 *   <li>SQL：连指定数据源 JDBC 执行，返回列 + 前 1000 行（或 updateCount）。</li>
 *   <li>PYTHON/JAVA/SHELL/SCALA：ProcessBuilder 本地执行，捕获 stdout/stderr + 超时（内网演示）。</li>
 * </ul>
 * 供 DevScriptController（手动）、DevWorkflowExecutor（节点）、DevOfflineExecutor（离线任务）复用。
 */
@Component
public class DevScriptExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;

    private static final long PROC_TIMEOUT_SEC = 300;
    private static final Map<String, String> SUFFIX = Map.of("PYTHON", ".py", "JAVA", ".java", "SHELL", ".sh", "SCALA", ".sc");
    private static final Map<String, String> RUNNER = Map.of("PYTHON", "python", "JAVA", "java", "SHELL", "bash", "SCALA", "scala");

    /** 执行已保存脚本（按 script_type 分派）。 */
    public Map<String, Object> runScript(long scriptId) {
        Map<String, Object> s = jdbc.queryForMap("SELECT script_type, datasource_id, content FROM meta.dev_script WHERE id=?", scriptId);
        String type = str(s.get("script_type"));
        if (type.isEmpty() || type.equalsIgnoreCase("SQL")) {
            return doRun(scriptId, lng(s.get("datasource_id")), str(s.get("content")));
        }
        return runProcess(scriptId, type.toUpperCase(), str(s.get("content")));
    }

    /** 临时执行 SQL（不落历史）。 */
    public Map<String, Object> runAdhoc(long dsId, String content) {
        return doRun(0, dsId, content);
    }

    /** 公共 SQL 执行（供离线任务复用，不落脚本历史）。 */
    public Map<String, Object> executeSql(long dsId, String content) {
        return doRun(0, dsId, content);
    }

    private Map<String, Object> doRun(long scriptId, long dsId, String content) {
        long runId = System.currentTimeMillis();
        Timestamp now = new Timestamp(runId);
        String status = "SUCCESS", errMsg = "";
        List<String> cols = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        long rowsRead = 0;
        try {
            DataSourceDescriptor ds = loader.load(dsId);
            DataSource pool = registry.getPool(ds);
            try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
                boolean hasRs = st.execute(content);
                if (hasRs) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
                        while (rs.next() && rows.size() < 1000) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                            rows.add(row);
                        }
                        rowsRead = rows.size();
                    }
                } else {
                    rowsRead = st.getUpdateCount();
                }
            }
        } catch (Exception e) {
            status = "FAIL";
            errMsg = rootMsg(e);
        }
        if (scriptId > 0) {
            try {
                jdbc.update("INSERT INTO meta.dev_script_run(id, script_id, status, rows_read, cols, error_msg, run_time) VALUES (?,?,?,?,?,?,?)",
                        runId, scriptId, status, rowsRead, cols.size(), errMsg, now);
            } catch (Exception ignored) {}
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", status);
        out.put("columns", cols);
        out.put("rows", rows);
        out.put("rowsRead", rowsRead);
        out.put("msg", errMsg);
        return out;
    }

    /** ProcessBuilder 本地执行 Python/Java/Shell/Scala（内网演示用，仅 SYS_ADMIN 可触发）。 */
    private Map<String, Object> runProcess(long scriptId, String type, String content) {
        long runId = System.currentTimeMillis();
        Timestamp now = new Timestamp(runId);
        String status = "SUCCESS", errMsg = "";
        StringBuilder out = new StringBuilder();
        Path tmp = null;
        try {
            String suffix = SUFFIX.getOrDefault(type, ".txt");
            tmp = Files.createTempFile("devscript-", suffix);
            Files.writeString(tmp, content == null ? "" : content, StandardCharsets.UTF_8);
            String runner = RUNNER.get(type);
            if (runner == null) throw new IllegalArgumentException("不支持的脚本类型: " + type);
            Process p = new ProcessBuilder(runner, tmp.toString()).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null && out.length() < 65000) out.append(line).append('\n');
            }
            boolean done = p.waitFor(PROC_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                status = "FAIL";
                errMsg = "执行超时(" + PROC_TIMEOUT_SEC + "s)";
            } else if (p.exitValue() != 0) {
                status = "FAIL";
                errMsg = "退出码 " + p.exitValue();
            }
        } catch (Exception e) {
            status = "FAIL";
            errMsg = rootMsg(e);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
        if (scriptId > 0) {
            try {
                jdbc.update("INSERT INTO meta.dev_script_run(id, script_id, status, rows_read, cols, error_msg, log_text, run_time) VALUES (?,?,?,?,?,?,?,?)",
                        runId, scriptId, status, 0L, 0, errMsg, out.toString(), now);
            } catch (Exception ignored) {}
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("status", status);
        r.put("log", out.toString());
        r.put("msg", errMsg);
        return r;
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
}
