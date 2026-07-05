package com.pharma.service.access.adapter;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 适配器：经 JDK 内置 HttpClient 走 REST（不依赖第三方 ES 客户端）。
 * 连通测试 GET /，列索引 GET /_cat/indices?format=json。
 */
public class ElasticsearchAdapter implements DataSourceAdapter {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Override public String type() { return "elasticsearch"; }
    @Override public boolean driverAvailable() { return true; }
    @Override public String jarHint() { return null; }
    @Override public String driverClassName() { return ""; }

    @Override public String buildUrl(DataSourceDescriptor ds) {
        int port = ds.port > 0 ? ds.port : 9200;
        return "http://" + (ds.host == null ? "127.0.0.1" : ds.host) + ":" + port;
    }

    private String auth(DataSourceDescriptor ds) {
        if (ds.username == null || ds.username.isEmpty()) return null;
        String raw = ds.username + ":" + (ds.password == null ? "" : ds.password);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes());
    }

    @Override
    public Map<String, Object> testConnection(DataSourceDescriptor ds) {
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(buildUrl(ds) + "/"))
                    .timeout(Duration.ofSeconds(5)).GET();
            String auth = auth(ds);
            if (auth != null) b.header("Authorization", auth);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("latency", System.currentTimeMillis() - t0);
                r.put("product", "Elasticsearch");
                r.put("version", resp.body().lines().filter(l -> l.contains("number")).findFirst().orElse("ok"));
                return r;
            }
            return Map.of("ok", false, "msg", "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return Map.of("ok", false, "msg", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> listTables(DataSource pool, String schema) {
        throw new UnsupportedOperationException("ES 列索引请走 testConnection/专用接口");
    }

    @Override
    public List<Map<String, Object>> describeTable(DataSource pool, String schema, String table) {
        throw new UnsupportedOperationException("ES 无 schema 描述");
    }

    /** 列索引（ES 专用，供 Controller 直接调用）：返回 [{name}]。 */
    public List<String> listIndices(DataSourceDescriptor ds) {
        List<String> out = new ArrayList<>();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(ds) + "/_cat/indices?format=json&h=index"))
                    .timeout(Duration.ofSeconds(8)).GET();
            String auth = auth(ds);
            if (auth != null) b.header("Authorization", auth);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return out;
            // 简易解析：抽取 "index":"xxx"
            String body = resp.body();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"index\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            while (m.find()) out.add(m.group(1));
        } catch (Exception ignored) {}
        return out;
    }
}
