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
 * <p>StarRocks 3.3.10 实测：property.auto.offset.reset=earliest 与 kafka_default_offsets 均不被 routine load 采纳
 * （默认从末端读 → 跳过已有积压，表现为 receivedBytes=0）；改用 kafka_partitions + kafka_offsets=OFFSET_BEGINNING
 * 逐分区显式从开头消费，积压 + 新消息皆入仓。CREATE 失败（作业已存在）则 RESUME。
 * <p>所有操作经独立 Connection + USE 目标库（主连接 url 无默认库，ROUTINE LOAD 需 current database）。
 */
@Component
public class RoutineLoadManager {

    @Autowired private DataSource dataSource;
    @Autowired private KafkaAdminHolder kafkaAdmin;
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

    /**
     * 启动：显式建 topic → 自动建目标表（主键列用 PRIMARY KEY 模型去重）→ CREATE-or-RESUME ROUTINE LOAD。
     * columnsJson：[{"col":"id","type":"BIGINT","pk":true},{"col":"name","type":"VARCHAR(50)"}] 或 ["x"]。
     * <p>显式建 topic 是为消除「StarRocks 消费 auto-create topic 入仓 receivedBytes=0」的边际；
     * 主键模型让被重复投递的行按主键 upsert 去重（键列须前置）。
     */
    public void start(long jobId, String topic, String db, String table, String columnsJson) {
        com.pharma.service.access.util.StarRocksDdlBuilder.ident(table);
        kafkaAdmin.ensureTopic(topic);  // 入仓0 修复点
        List<ColDef> cols = parseCols(columnsJson);
        if (cols.isEmpty()) throw new IllegalArgumentException("未配置列");
        List<ColDef> pk = cols.stream().filter(c -> c.pk).collect(Collectors.toList());
        // 主键列须为 schema 前缀：pk 在前、其余按声明顺序
        List<ColDef> ordered = new ArrayList<>(pk);
        cols.stream().filter(c -> !c.pk).forEach(ordered::add);
        ColDef key0 = ordered.get(0);
        String colDdl = ordered.stream().map(c -> "`" + c.name + "` " + c.type).collect(Collectors.joining(", "));
        String colList = ordered.stream().map(c -> "`" + c.name + "`").collect(Collectors.joining(","));
        String name = "rl_" + jobId;

        withDb(db, (conn, st) -> {
            String createTable;
            if (!pk.isEmpty()) {
                String pkList = pk.stream().map(c -> "`" + c.name + "`").collect(Collectors.joining(","));
                createTable = "CREATE TABLE IF NOT EXISTS `" + table + "` (" + colDdl
                        + ") PRIMARY KEY(" + pkList + ") DISTRIBUTED BY HASH(`" + pk.get(0).name
                        + "`) BUCKETS 3 PROPERTIES(\"replication_num\"=\"1\")";
            } else {
                createTable = "CREATE TABLE IF NOT EXISTS `" + table + "` (" + colDdl
                        + ") DUPLICATE KEY(`" + key0.name + "`) DISTRIBUTED BY HASH(`" + key0.name
                        + "`) BUCKETS 3 PROPERTIES(\"replication_num\"=\"1\")";
            }
            st.execute(createTable);
            String jp = "[" + ordered.stream().map(c -> "\"$." + c.name + "\"").collect(Collectors.joining(", ")) + "]";
            // StarRocks 3.3.10 实测：property.auto.offset.reset=earliest 与 kafka_default_offsets 均不被采纳
            // （默认从末端读 → 跳过已有积压）。改用 kafka_partitions + kafka_offsets=OFFSET_BEGINNING 显式从开头消费。
            int np = kafkaAdmin.partitionCount(topic);
            String kafkaParts = java.util.stream.IntStream.range(0, np).mapToObj(String::valueOf).collect(Collectors.joining(","));
            String kafkaOffsets = java.util.stream.IntStream.range(0, np).mapToObj(i -> "OFFSET_BEGINNING").collect(Collectors.joining(","));
            String create = "CREATE ROUTINE LOAD " + name + " ON `" + table + "` "
                    + "COLUMNS(" + colList + ") "
                    + "PROPERTIES('format'='json', 'jsonpaths'='" + jp + "') "
                    + "FROM KAFKA('kafka_broker_list'='" + internalBroker + "', "
                    + "'kafka_topic'='" + topic + "', "
                    + "'kafka_partitions'='" + kafkaParts + "', "
                    + "'kafka_offsets'='" + kafkaOffsets + "')";
            try { st.execute(create); }
            catch (Exception e) { try { st.execute("RESUME ROUTINE LOAD FOR " + name); } catch (Exception ignored) {} }
        });
    }

    /**
     * 作业详情：在目标库 SHOW ROUTINE LOAD 中匹配 rl_&lt;jobId&gt;，解析 Statistic JSON，暴露
     * state/loadedRows/errorRows/totalRows/receivedBytes/reason/errorLogUrls/progress。
     * 容错：列缺失或解析失败返回默认值（0/空）。
     */
    public Map<String, Object> detail(long jobId, String db) {
        String target = "rl_" + jobId;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", "UNKNOWN");
        out.put("loadedRows", 0L);
        out.put("errorRows", 0L);
        out.put("totalRows", 0L);
        out.put("receivedBytes", 0L);
        out.put("reason", "");
        out.put("errorLogUrls", "");
        out.put("progress", "");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("USE `" + db + "`");
            try (ResultSet rs = st.executeQuery("SHOW ROUTINE LOAD")) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                while (rs.next()) {
                    if (!target.equals(rs.getString("Name"))) continue;
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), str(rs.getObject(i)));
                    out.put("state", v(row, "State"));
                    String stat = v(row, "Statistic");
                    parseStatistic(stat, out);
                    out.put("reason", v(row, "ReasonOfStateChanged"));
                    out.put("errorLogUrls", v(row, "ErrorLogUrls"));
                    out.put("progress", v(row, "Progress"));
                    break;
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void parseStatistic(String s, Map<String, Object> out) {
        if (s == null || s.isBlank()) return;
        try {
            JsonNode n = json.readTree(s);
            out.put("receivedBytes", n.path("receivedBytes").asLong(0));
            out.put("errorRows", n.path("errorRows").asLong(0));
            out.put("loadedRows", n.path("loadedRows").asLong(0));
            out.put("totalRows", n.path("totalRows").asLong(0));
        } catch (Exception ignored) {}
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
                    String c = n.has("col") ? n.get("col").asText() : (n.has("name") ? n.get("name").asText() : "");
                    if (c.isEmpty()) continue;
                    String t = n.has("type") ? n.get("type").asText() : "VARCHAR(255)";
                    boolean pk = n.has("pk") && n.get("pk").asBoolean();
                    out.add(new ColDef(c, t, pk));
                } else {
                    out.add(new ColDef(n.asText(), "VARCHAR(255)", false));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static class ColDef { final String name; final String type; final boolean pk; ColDef(String n, String t, boolean p) { name = n; type = t; pk = p; } }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    /** 行内取值（列名大小写容错：StarRocks JDBC 返回的列标签大小写不完全统一）。 */
    private static String v(Map<String, String> row, String key) {
        String s = row.get(key);
        if (s != null) return s;
        for (Map.Entry<String, String> e : row.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return "";
    }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
