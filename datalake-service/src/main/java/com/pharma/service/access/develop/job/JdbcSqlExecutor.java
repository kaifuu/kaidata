package com.pharma.service.access.develop.job;

import com.pharma.service.access.develop.DevScriptExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * JDBC SQL 作业（job_type=jdbc_sql）：直接在绑定数据源上执行 SQL。
 * <p>沿用原离线任务的方式（DevScriptExecutor.executeSql），兼容历史任务。
 */
@Component
public class JdbcSqlExecutor implements DevJobExecutor {

    @Autowired private DevScriptExecutor scriptExecutor;

    @Override public String jobType() { return "jdbc_sql"; }

    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        long dsId = lng(task.get("datasource_id"));
        String sql = str(task.get("sql_content"));
        log.append("JDBC SQL 数据源id=").append(dsId).append("\n");
        log.append("SQL: ").append(sql.length() > 200 ? sql.substring(0, 200) + "..." : sql).append("\n");
        Map<String, Object> r = scriptExecutor.executeSql(dsId, sql);
        if (!"SUCCESS".equals(str(r.get("status")))) {
            throw new RuntimeException("JDBC 执行失败: " + r.get("msg"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineJobId", "");
        out.put("rowsRead", lng(r.get("rowsRead")));
        Object columns = r.get("columns");
        out.put("cols", columns instanceof List ? ((List<?>) columns).size() : 0);
        log.append("完成 rowsRead=").append(out.get("rowsRead")).append(" cols=").append(out.get("cols")).append("\n");
        return out;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
