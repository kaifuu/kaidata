package com.pharma.service.access.develop;

import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据开发 SQL 执行器：连指定数据源执行 SQL，返回列 + 前 1000 行结果（或 updateCount）。
 * 供 DevScriptController（手动执行）与 DevWorkflowExecutor（工作流节点）复用。
 */
@Component
public class DevScriptExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;

    /** 执行已保存脚本。 */
    public Map<String, Object> runScript(long scriptId) {
        Map<String, Object> script = jdbc.queryForMap("SELECT datasource_id, content FROM meta.dev_script WHERE id=?", scriptId);
        return doRun(scriptId, ((Number) script.get("datasource_id")).longValue(), String.valueOf(script.get("content")));
    }

    /** 临时执行（不落历史）。 */
    public Map<String, Object> runAdhoc(long dsId, String content) {
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

    private static String rootMsg(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + m;
    }
}
