package com.pharma.service.access.develop.job;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FlinkJar 作业（job_type=flink_jar）：本地 jar → 上传 Flink REST /jars/upload → /jars/{id}/run。
 * <p>jar 文件存本地（${pharma.engines.jar-dir}，默认 data/flink-jars），由前端上传接口登记。
 */
@Component
public class FlinkJarExecutor extends AbstractHttpExecutor {

    @Value("${pharma.engines.flink.rest:http://localhost:8081}") private String flinkRest;
    @Value("${pharma.engines.jar-dir:data/flink-jars}") private String jarDir;

    @Override public String jobType() { return "flink_jar"; }

    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        Map<String, Object> cfg = parseJson(str(task.get("config_json")));
        String jarName = str(cfg.get("jarName"));
        String mainClass = str(cfg.get("mainClass"));
        String args = str(cfg.get("args"));
        int parallelism = intOr(cfg.get("parallelism"), 1);
        if (jarName.isEmpty()) throw new RuntimeException("未配置 jar 名");
        Path jarPath = Paths.get(jarDir, jarName);
        if (!Files.exists(jarPath)) throw new RuntimeException("jar 不存在: " + jarPath + "（请先上传）");
        byte[] jarBytes = Files.readAllBytes(jarPath);
        log.append("jar: ").append(jarPath).append(" (").append(jarBytes.length).append(" B)\n");

        // 1) 上传 jar 到 Flink（multipart/form-data）
        String boundary = "----flink" + System.currentTimeMillis();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"jarfile\"; filename=\"" + jarName + "\"\r\nContent-Type: application/java-archive\r\n\r\n";
        bos.write(head.getBytes(StandardCharsets.UTF_8));
        bos.write(jarBytes);
        bos.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> up = http.send(HttpRequest.newBuilder()
                .uri(URI.create(flinkRest + "/jars/upload")).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        if (up.statusCode() >= 300) throw new RuntimeException("Flink 上传失败 " + up.statusCode() + ": " + up.body());
        String jarId = str(parseJson(up.body()).get("filename"));
        log.append("已上传 Flink: ").append(jarId).append("\n");

        // 2) 提交作业
        Map<String, Object> runBody = new LinkedHashMap<>();
        if (!mainClass.isEmpty()) runBody.put("entryClass", mainClass);
        if (!args.isEmpty()) runBody.put("programArgs", args);
        runBody.put("parallelism", parallelism);
        HttpResponse<String> run = http.send(HttpRequest.newBuilder()
                .uri(URI.create(flinkRest + "/jars/" + URLEncoder.encode(jarId, StandardCharsets.UTF_8) + "/run"))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(runBody))).build(), HttpResponse.BodyHandlers.ofString());
        if (run.statusCode() >= 300) throw new RuntimeException("Flink 提交失败 " + run.statusCode() + ": " + run.body());
        String jobId = str(parseJson(run.body()).get("jobid"));
        log.append("作业已提交: jobid=").append(jobId).append("\n");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineJobId", jobId);
        out.put("rowsRead", 0);
        return out;
    }
}
