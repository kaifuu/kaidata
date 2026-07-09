package com.pharma.service.access.develop.job;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 作业执行器 HTTP 基类：复用 HttpClient / ObjectMapper / JSON 解析 / 字符串助手。
 * <p>FlinkSql/FlinkJar/FlinkDag/KettleHop 执行器都需调 REST，继承此类避免重复。
 */
public abstract class AbstractHttpExecutor implements DevJobExecutor {

    protected final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    protected final ObjectMapper json = new ObjectMapper();

    protected Map<String, Object> postJson(String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (body == null) b.POST(HttpRequest.BodyPublishers.noBody());
        else b.POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new RuntimeException("HTTP " + resp.statusCode() + " @ " + url + " : " + resp.body());
        return parseJson(resp.body());
    }

    protected Map<String, Object> postAuth(String url, String body, String basicAuth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (basicAuth != null && !basicAuth.isEmpty()) b.header("Authorization", "Basic " + basicAuth);
        if (body == null) b.POST(HttpRequest.BodyPublishers.noBody());
        else b.POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new RuntimeException("HTTP " + resp.statusCode() + " @ " + url + " : " + resp.body());
        return parseJson(resp.body());
    }

    protected Map<String, Object> getJson(String url) throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new RuntimeException("HTTP GET " + resp.statusCode() + " @ " + url + " : " + resp.body());
        return parseJson(resp.body());
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseJson(String s) {
        try { return json.readValue(s == null || s.isBlank() ? "{}" : s, Map.class); }
        catch (Exception e) { throw new RuntimeException("解析 JSON 失败: " + s, e); }
    }

    protected static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    protected static int intOr(Object o, int def) { if (o == null) return def; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return def; } }
}
