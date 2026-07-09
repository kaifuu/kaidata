package com.pharma.service.access.develop.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FlinkSQL 作业（job_type=flink_sql）：提交到 Flink SQL Gateway（默认 :8083）。
 * <p>流程：建会话 → 按 ';' 拆分逐条 statements → 轮询 operation status → 关会话。
 */
@Component
public class FlinkSqlExecutor extends AbstractHttpExecutor {

    @Value("${pharma.engines.flink.sql-gateway:http://localhost:8083}") private String gateway;

    @Override public String jobType() { return "flink_sql"; }

    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        String sql = str(task.get("sql_content"));
        if (sql.isEmpty()) throw new RuntimeException("FlinkSQL 内容为空");
        Map<String, Object> cfg = parseJson(str(task.get("config_json")));
        int parallelism = intOr(cfg.get("parallelism"), 2);

        // 1) 建会话
        Map<String, Object> sessProps = new LinkedHashMap<>();
        sessProps.put("parallelism", String.valueOf(parallelism));
        String sessionBody = json.writeValueAsString(Map.of("properties", sessProps));
        Map<String, Object> sess = postJson(gateway + "/v1/sessions", sessionBody);
        String sh = str(sess.get("sessionHandle"));
        log.append("SQL Gateway 会话: ").append(sh).append(" parallelism=").append(parallelism).append("\n");

        String engineJobId = "";
        try {
            for (String stmt : sql.split(";")) {
                String s = stmt.trim();
                if (s.isEmpty()) continue;
                Map<String, Object> exec = postJson(gateway + "/v1/sessions/" + sh + "/statements",
                        json.writeValueAsString(Map.of("statement", s)));
                String op = str(exec.get("operationHandle"));
                String preview = s.length() > 80 ? s.substring(0, 80) + "..." : s;
                log.append("  提交: ").append(preview).append(" → op=").append(op).append("\n");
                pollOp(sh, op, log);
                String up = s.toUpperCase();
                if (up.startsWith("INSERT INTO") || up.startsWith("INSERT OVERWRITE")) engineJobId = op;
            }
        } finally {
            try {
                http.send(HttpRequest.newBuilder().uri(URI.create(gateway + "/v1/sessions/" + sh)).DELETE().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineJobId", engineJobId);
        out.put("rowsRead", 0);
        log.append("FlinkSQL 提交完成\n");
        return out;
    }

    private void pollOp(String sh, String op, StringBuilder log) throws Exception {
        String url = gateway + "/v1/sessions/" + sh + "/operations/" + op + "/status";
        for (int i = 0; i < 120; i++) {
            Map<String, Object> st = getJson(url);
            String status = str(st.get("status"));
            if ("FINISHED".equals(status)) return;
            if ("ERROR".equals(status) || "CLOSED".equals(status) || "CLOSING".equals(status))
                throw new RuntimeException("SQL 执行失败: " + st);
            Thread.sleep(1000);
        }
        log.append("  [轮询超时，继续后续语句]\n");
    }
}
