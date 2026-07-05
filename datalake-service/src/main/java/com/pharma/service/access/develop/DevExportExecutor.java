package com.pharma.service.access.develop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.access.util.TypeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据接出执行器：从源数据源查询 → 写入目标数据库（建表+批写）或推送到 REST 接口。
 */
@Component
public class DevExportExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public Map<String, Object> run(long exportId) {
        long runId = System.currentTimeMillis();
        String status = "SUCCESS", errMsg = "";
        long outRows = 0;
        try {
            Map<String, Object> e = jdbc.queryForMap("SELECT source_ds_id, source_query, target_type, target_config FROM meta.dev_export WHERE id=?", exportId);
            long srcDs = ((Number) e.get("source_ds_id")).longValue();
            String query = String.valueOf(e.get("source_query"));
            String targetType = String.valueOf(e.get("target_type"));
            Map<String, Object> cfg = parseCfg(String.valueOf(e.get("target_config")));

            DataSource srcPool = registry.getPool(loader.load(srcDs));
            List<StarRocksDdlBuilder.ColumnDef> cols = new ArrayList<>();
            List<Object[]> rows = new ArrayList<>();
            try (Connection c = srcPool.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(query)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                for (int i = 1; i <= n; i++) cols.add(new StarRocksDdlBuilder.ColumnDef(md.getColumnLabel(i),
                        TypeMapper.toStarRocks(md.getColumnType(i), md.getColumnTypeName(i), md.getPrecision(i), md.getScale(i))));
                while (rs.next() && rows.size() < 10000) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) row[i] = rs.getObject(i + 1);
                    rows.add(row);
                }
            }
            if ("db".equals(targetType)) {
                long tgtDs = lng(cfg.get("datasource_id"));
                String table = str(cfg.get("table"));
                DataSource tgtPool = registry.getPool(loader.load(tgtDs));
                outRows = writeToDb(tgtPool, table, cols, rows);
            } else {
                outRows = postToApi(str(cfg.get("url")), cols, rows);
            }
        } catch (Exception ex) {
            status = "FAIL";
            errMsg = rootMsg(ex);
        }
        try {
            jdbc.update("INSERT INTO meta.dev_export_run(id, export_id, status, rows_out, error_msg, run_time) VALUES (?,?,?,?,?,?)",
                    runId, exportId, status, outRows, errMsg, new Timestamp(System.currentTimeMillis()));
        } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("status", status);
        out.put("rowsOut", outRows);
        out.put("msg", errMsg);
        return out;
    }

    private int writeToDb(DataSource tgtPool, String table, List<StarRocksDdlBuilder.ColumnDef> cols, List<Object[]> rows) throws Exception {
        String[] sp = splitTable(table);
        StarRocksDdlBuilder.ident(sp[1]);
        String db = sp[0] == null ? "ods" : sp[0];
        String ddl = StarRocksDdlBuilder.build(db, sp[1], cols, null, false);
        JdbcTemplate tj = new JdbcTemplate(tgtPool);
        tj.execute(ddl);
        String colList = cols.stream().map(c -> "`" + c.name + "`").collect(Collectors.joining(","));
        String ph = cols.stream().map(x -> "?").collect(Collectors.joining(","));
        String insert = "INSERT INTO `" + db + "`.`" + sp[1] + "` (" + colList + ") VALUES (" + ph + ")";
        int[] n = tj.batchUpdate(insert, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int idx) throws java.sql.SQLException {
                Object[] row = rows.get(idx);
                for (int k = 0; k < row.length; k++) ps.setObject(k + 1, row[k]);
            }
            @Override public int getBatchSize() { return rows.size(); }
        });
        int sum = 0; for (int x : n) sum += Math.max(x, 0);
        return sum;
    }

    private int postToApi(String url, List<StarRocksDdlBuilder.ColumnDef> cols, List<Object[]> rows) throws Exception {
        if (url == null || url.isEmpty()) throw new IllegalArgumentException("target_config.url 未配置");
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) m.put(cols.get(i).name, i < row.length ? row[i] : null);
            payload.add(m);
        }
        String body = json.writeValueAsString(payload);
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new RuntimeException("API 返回 " + resp.statusCode());
        return payload.size();
    }

    private String[] splitTable(String t) {
        int i = t.lastIndexOf('.');
        return i < 0 ? new String[]{null, t} : new String[]{t.substring(0, i), t.substring(i + 1)};
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCfg(String cfg) {
        try { return json.readValue(cfg == null || cfg.isBlank() ? "{}" : cfg, Map.class); } catch (Exception e) { return Map.of(); }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static String rootMsg(Throwable e) { Throwable c = e; for (int i = 0; i < 6 && c.getCause() != null && c.getCause() != c; i++) c = c.getCause(); String m = c.getMessage(); return m == null ? c.getClass().getSimpleName() : c.getClass().getSimpleName() + ": " + m; }
}
