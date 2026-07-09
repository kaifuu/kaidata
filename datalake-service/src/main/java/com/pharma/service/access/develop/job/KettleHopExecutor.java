package com.pharma.service.access.develop.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kettle/Hop 作业（job_type=kettle_hop）：调 Apache Hop Server REST 执行 workflow（默认 :8082，Basic Auth）。
 * <p>当前按 config_json.workflowName 直接调 Hop Server；dag_json → Hop metadata XML 翻译留作后续增强。
 */
@Component
public class KettleHopExecutor extends AbstractHttpExecutor {

    @Value("${pharma.engines.hop.server:http://localhost:8082}") private String hopServer;
    @Value("${pharma.engines.hop.user:cluster}") private String hopUser;
    @Value("${pharma.engines.hop.pass:cluster}") private String hopPass;

    @Override public String jobType() { return "kettle_hop"; }

    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        Map<String, Object> cfg = parseJson(str(task.get("config_json")));
        String workflowName = str(cfg.get("workflowName"));
        if (workflowName.isEmpty()) throw new RuntimeException("未配置 Hop workflow 名");
        log.append("Hop Server: ").append(hopServer).append(" workflow=").append(workflowName).append("\n");

        String auth = Base64.getEncoder().encodeToString((hopUser + ":" + hopPass).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                .uri(URI.create(hopServer + "/hop/execute/" + URLEncoder.encode(workflowName, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Basic " + auth)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new RuntimeException("Hop 执行失败 " + resp.statusCode() + ": " + resp.body());
        String execId = str(parseJson(resp.body()).get("id"));
        log.append("Hop 执行已提交: id=").append(execId).append("\n");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineJobId", execId);
        out.put("rowsRead", 0);
        return out;
    }
}
