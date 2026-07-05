package com.pharma.service.access.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * StarRocks ROUTINE LOAD 管理：Kafka topic → StarRocks 目标表（复用现有入仓路径）。
 * <p>严守 StarRocks 3.3.10 实测坑：不用 desired_concurrent/kafka_group_id/kafka_default_offsets，
 * 用 property.auto.offset.reset=earliest；CREATE 失败（作业已存在）则 RESUME。
 * <p>所有操作经独立 Connection + USE 目标库（主连接 url 无默认库，ROUTINE LOAD 需 current database）。
 */
@Component
public class RoutineLoadManager {

    @Autowired private DataSource dataSource;
    @Value("${pharma.kafka.internal-broker:kafka:9092}") private String internalBroker;
    private final ObjectMapper json = new ObjectMapper();

    @FunctionalInterface private interface DbAction { void run(Connection c, Statement st) throws Exception; }
    private void withDb(String db, DbAction a) {
        com.pharma.service.access.util.StarRocksDdlBuilder.ident(db);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("USE `" + db + "`");
            a.run(c, st);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(rootMsg(e), e);
        }
    }

    /** 启动：自动建目标表 + CREATE-or-RESUME ROUTINE LOAD。columnsJson：[{"col":"x","type":"VARCHAR(50)"}] 或 ["x"]。 */
    public void start(long jobId, String topic, String db, String table, String columnsJson) {
        com.pharma.service.access.util.StarRocksDdlBuilder.ident(table);
        List<ColDef> cols = parseCols(columnsJson);
        if (cols.isEmpty()) throw new IllegalArgumentException("未配置列");
        String colDdl = cols.stream().map(c -> "`" + c.name + "` " + c.type).collect(Collectors.joining(", "));
        String colList = cols.stream().map(c -> "`" + c.name + "`").collect(Collectors.joining(","));
        String name = "rl_" + jobId;

        withDb(db, (conn, st) -> {
            st.execute("CREATE TABLE IF NOT EXISTS `" + table + "` (" + colDdl
                    + ") DUPLICATE KEY(`" + cols.get(0).name + "`) DISTRIBUTED BY HASH(`" + cols.get(0).name
                    + "`) BUCKETS 3 PROPERTIES(\"replication_num\"=\"1\")");
            String jp = "[" + cols.stream().map(c -> "\"$." + c.name + "\"").collect(Collectors.joining(", ")) + "]";
            String create = "CREATE ROUTINE LOAD " + name + " ON `" + table + "` "
                    + "COLUMNS(" + colList + ") "
                    + "PROPERTIES('format'='json', 'jsonpaths'='" + jp + "') "
                    + "FROM KAFKA('kafka_broker_list'='" + internalBroker + "', "
                    + "'kafka_topic'='" + topic + "', "
                    + "'property.auto.offset.reset'='earliest')";
            try { st.execute(create); }
            catch (Exception e) { try { st.execute("RESUME ROUTINE LOAD FOR " + name); } catch (Exception ignored) {} }
        });
    }

    public void stop(long jobId, String db) {
        try { withDb(db, (conn, st) -> st.execute("STOP ROUTINE LOAD FOR rl_" + jobId)); }
        catch (Exception ignored) {}
    }

    public String state(long jobId, String db) {
        String target = "rl_" + jobId;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("USE `" + db + "`");
            try (ResultSet rs = st.executeQuery("SHOW ROUTINE LOAD")) {
                while (rs.next()) if (target.equals(rs.getString("Name"))) return rs.getString("State");
            }
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }

    /** 列出 ods 库现有 ROUTINE LOAD（rl_env/rl_batch/rl_qc 等）。 */
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("USE ods");
            try (ResultSet rs = st.executeQuery("SHOW ROUTINE LOAD")) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<ColDef> parseCols(String columnsJson) {
        List<ColDef> out = new ArrayList<>();
        if (columnsJson == null || columnsJson.isBlank()) return out;
        try {
            JsonNode root = json.readTree(columnsJson);
            for (JsonNode n : root) {
                if (n.isObject()) {
                    String c = n.has("col") ? n.get("col").asText() : n.get("name").asText();
                    String t = n.has("type") ? n.get("type").asText() : "VARCHAR(255)";
                    out.add(new ColDef(c, t));
                } else {
                    out.add(new ColDef(n.asText(), "VARCHAR(255)"));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static class ColDef { final String name; final String type; ColDef(String n, String t) { name = n; type = t; } }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
