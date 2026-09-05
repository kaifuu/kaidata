package com.pharma.service.access.adapter;

import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Iceberg 湖适配器：经 REST Catalog（tabulario/iceberg-rest:8181）访问 MinIO 上的湖表。
 * <p>表单语义：host/port = REST Catalog 地址；username/password = S3 AccessKey/SecretKey；
 * db_name = 默认命名空间；props 可带 {"s3.endpoint":"http://localhost:9000"}（MinIO 对外地址）。
 * <p>登记约定：{@link #listTables} 返回的 schema_name 为 "iceberg_catalog.&lt;ns&gt;" ——
 * 即主库 StarRocks External Catalog 下的三段名前缀，使质量规则/标准核验/资产取数等
 * "按 ds+schema.table 直查"的路径经 {@link DataSourceAdapterRegistry#getPool}（iceberg 返回主库池）
 * 后零改动可用（SELECT ... FROM iceberg_catalog.ns.tbl）。
 */
public class IcebergAdapter implements DataSourceAdapter {

    /** 元数据查询/数据探查统一经主库 StarRocks External Catalog 的三段名前缀。 */
    public static final String SR_CATALOG = "iceberg_catalog";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override public String type() { return "iceberg"; }
    @Override public boolean driverAvailable() { return true; }
    @Override public String jarHint() { return null; }
    @Override public String driverClassName() { return ""; }

    @Override public String buildUrl(DataSourceDescriptor ds) {
        int port = ds.port > 0 ? ds.port : 8181;
        return "http://" + (ds.host == null || ds.host.isEmpty() ? "127.0.0.1" : ds.host) + ":" + port;
    }

    // ---------------- 连通测试（REST /v1/config，无需建 catalog 客户端） ----------------

    @Override
    public Map<String, Object> testConnection(DataSourceDescriptor ds) {
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(buildUrl(ds) + "/v1/config"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("ok", true);
                r.put("latency", System.currentTimeMillis() - t0);
                r.put("product", "Iceberg REST Catalog");
                r.put("version", "tabulario/iceberg-rest");
                return r;
            }
            return Map.of("ok", false, "msg", "HTTP " + resp.statusCode() + "（请确认 iceberg-rest 容器已启动）");
        } catch (Exception e) {
            return Map.of("ok", false, "msg", e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "（默认地址 http://localhost:8181，需先 bash docker/bring-up.sh）");
        }
    }

    // ---------------- 源表（湖表）列举 / 结构 ----------------
    // pool 参数忽略：iceberg 不走 JDBC 连接池（getPool 对 iceberg 返回主库 SR 池，仅数据探查复用）。

    @Override
    public List<Map<String, Object>> listTables(DataSource pool, String schema) {
        String ns = nsOf(schema);
        List<Map<String, Object>> out = new ArrayList<>();
        try (RESTCatalog catalog = catalog(null)) {
            // schema 为空 → 列全部命名空间的表（工作台左树按 ns 分组）；指定 → 单命名空间
            List<Namespace> nss = (ns == null)
                    ? new ArrayList<>(catalog.listNamespaces())
                    : List.of(Namespace.of(ns));
            for (Namespace n : nss) {
                for (TableIdentifier t : catalog.listTables(n)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("schema_name", SR_CATALOG + "." + t.namespace().toString());
                    m.put("comment", "");
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("湖表列举失败：" + rootMsg(e), e);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> describeTable(DataSource pool, String schema, String table) {
        String ns = nsOf(schema);
        try (RESTCatalog catalog = catalog(null)) {
            Schema s = catalog.loadTable(TableIdentifier.of(ns == null ? defaultNs() : ns, table)).schema();
            List<Map<String, Object>> out = new ArrayList<>();
            for (var f : s.columns()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.name());
                m.put("type", f.type().toString());
                m.put("comment", f.doc() == null ? "" : f.doc());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("湖表结构读取失败：" + rootMsg(e), e);
        }
    }

    // ===================== 共享客户端构造（IcebergWriter 复用） =====================

    /**
     * 构建 REST Catalog 客户端（S3FileIO 指向 MinIO）。
     *
     * @param ds 数据源描述；null 时用本地默认（localhost:8181 + minioadmin）——
     *           供 listTables/describeTable 等无数据源上下文的调用（默认命名空间取 demo）
     */
    public static RESTCatalog catalog(DataSourceDescriptor ds) {
        String uri = ds == null ? "http://localhost:8181" : new IcebergAdapter().buildUrl(ds);
        String ak = (ds == null || ds.username == null || ds.username.isEmpty()) ? "minioadmin" : ds.username;
        String sk = (ds == null || ds.password == null || ds.password.isEmpty()) ? "minioadmin" : ds.password;
        String s3 = "http://localhost:9000";
        if (ds != null && ds.props != null && !ds.props.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode p =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(ds.props);
                if (p.hasNonNull("s3.endpoint")) s3 = p.get("s3.endpoint").asText();
            } catch (Exception ignored) {}
        }
        Map<String, String> props = new HashMap<>();
        props.put(org.apache.iceberg.CatalogProperties.URI, uri);
        props.put(org.apache.iceberg.CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.aws.s3.S3FileIO");
        props.put("s3.endpoint", s3);
        props.put("s3.access-key-id", ak);
        props.put("s3.secret-access-key", sk);
        props.put("s3.path-style-access", "true");
        props.put("s3.region", "us-east-1");
        props.put("client.region", "us-east-1");   // AwsClientProperties：SDK 客户端 region（MinIO 必须显式给）
        RESTCatalog catalog = new RESTCatalog();
        catalog.initialize("iceberg", props);
        return catalog;
    }

    public static String defaultNs() { return "demo"; }

    /** 入参可能是裸命名空间（demo）或三段名前缀形态（iceberg_catalog.demo）；返回裸命名空间。 */
    public static String nsOf(String schema) {
        if (schema == null || schema.isBlank()) return null;
        return schema.startsWith(SR_CATALOG + ".") ? schema.substring(SR_CATALOG.length() + 1) : schema;
    }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
