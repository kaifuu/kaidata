package com.pharma.service.access.adapter;

import com.pharma.service.access.util.SqlBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 JDBC 适配器：一个类覆盖 mysql/starrocks/doris/postgresql/greenplum/opengauss/
 * clickhouse/sqlserver/oracle/tdengine/hive 等 11 种 MySQL/标准/方言数据库。
 * <p>
 * 驱动 jar 缺失时 {@link #driverAvailable} 为 false，自动降级为占位（不阻断构建/启动）。
 * 元数据查询：默认走 information_schema；oracle 用 ALL_*，tdengine/hive 用 SHOW。
 */
public class JdbcAdapter implements DataSourceAdapter {

    private final String type;
    private final String driverClass;
    private final int defaultPort;
    private final boolean driverAvailable;

    private JdbcAdapter(String type, String driverClass, int defaultPort) {
        this.type = type;
        this.driverClass = driverClass;
        this.defaultPort = defaultPort;
        boolean ok = false;
        try { Class.forName(driverClass); ok = true; } catch (Throwable ignored) {}
        this.driverAvailable = ok;
    }

    public static JdbcAdapter of(String type) {
        switch (type) {
            case "mysql":       return new JdbcAdapter("mysql", "com.mysql.cj.jdbc.Driver", 3306);
            case "starrocks":   return new JdbcAdapter("starrocks", "com.mysql.cj.jdbc.Driver", 9030);
            case "doris":       return new JdbcAdapter("doris", "com.mysql.cj.jdbc.Driver", 9030);
            case "postgresql":  return new JdbcAdapter("postgresql", "org.postgresql.Driver", 5432);
            case "greenplum":   return new JdbcAdapter("greenplum", "org.postgresql.Driver", 5432);
            case "opengauss":   return new JdbcAdapter("opengauss", "org.postgresql.Driver", 5432);
            case "clickhouse":  return new JdbcAdapter("clickhouse", "com.clickhouse.jdbc.ClickHouseDriver", 8123);
            case "sqlserver":   return new JdbcAdapter("sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433);
            case "oracle":      return new JdbcAdapter("oracle", "oracle.jdbc.OracleDriver", 1521);
            case "tdengine":    return new JdbcAdapter("tdengine", "com.taosdata.jdbc.rs.RestfulDriver", 6041);
            case "hive":        return new JdbcAdapter("hive", "org.apache.hive.jdbc.HiveDriver", 10000);
            default: throw new IllegalArgumentException("未知 JDBC 类型: " + type);
        }
    }

    @Override public String type() { return type; }
    @Override public boolean driverAvailable() { return driverAvailable; }
    @Override public String jarHint() { return null; }
    @Override public String driverClassName() { return driverClass; }

    @Override
    public String buildUrl(DataSourceDescriptor ds) {
        int port = ds.port > 0 ? ds.port : defaultPort;
        String h = ds.host == null ? "127.0.0.1" : ds.host;
        String db = ds.dbName == null ? "" : ds.dbName;
        switch (type) {
            case "mysql": case "starrocks": case "doris":
                return "jdbc:mysql://" + h + ":" + port + "/" + db
                        + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
            case "postgresql": case "greenplum": case "opengauss":
                return "jdbc:postgresql://" + h + ":" + port + "/" + db;
            case "clickhouse":
                return "jdbc:ch://" + h + ":" + port + "/" + db;
            case "sqlserver":
                return "jdbc:sqlserver://" + h + ":" + port + ";databaseName=" + db + ";encrypt=false";
            case "oracle":
                return "jdbc:oracle:thin:@" + h + ":" + port + "/" + db;
            case "tdengine":
                return "jdbc:TAOS-RS://" + h + ":" + port + "/" + db;
            case "hive":
                return "jdbc:hive2://" + h + ":" + port + "/" + db;
            default:
                return "jdbc:mysql://" + h + ":" + port + "/" + db;
        }
    }

    @Override
    public Map<String, Object> testConnection(DataSourceDescriptor ds) {
        if (!driverAvailable) {
            return Map.of("ok", false, "msg", "驱动未就绪：" + driverClass + "（请检查依赖或放置对应 jar 后重启）");
        }
        long t0 = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(buildUrl(ds),
                ds.username == null ? "" : ds.username,
                ds.password == null ? "" : ds.password)) {
            DatabaseMetaData md = c.getMetaData();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("latency", System.currentTimeMillis() - t0);
            r.put("product", md.getDatabaseProductName());
            r.put("version", md.getDatabaseProductVersion());
            return r;
        } catch (Exception e) {
            return Map.of("ok", false, "msg", rootMsg(e));
        }
    }

    @Override
    public List<Map<String, Object>> listTables(DataSource pool, String schema) {
        switch (type) {
            case "oracle":
                return query(pool, oracleTablesSql(schema));
            case "tdengine":
            case "hive":
                return querySimple(pool, "SHOW TABLES");
            default:
                return query(pool, SqlBuilder.listTablesSql(schema));
        }
    }

    @Override
    public List<Map<String, Object>> describeTable(DataSource pool, String schema, String table) {
        switch (type) {
            case "oracle":
                return query(pool, oracleColumnsSql(schema, table));
            case "tdengine":
            case "hive":
                return queryDescribe(pool, table);
            default:
                return query(pool, SqlBuilder.describeSql(schema, table));
        }
    }

    // ---------------- SQL 构造（方言） ----------------

    private static String oracleTablesSql(String schema) {
        String base = "SELECT table_name AS name, owner AS schema_name, '' AS comment FROM all_tables";
        if (schema != null && !schema.isEmpty()) {
            SqlBuilder.ident(schema);
            return base + " WHERE owner='" + schema.toUpperCase() + "' ORDER BY table_name";
        }
        return base + " ORDER BY owner, table_name";
    }

    private static String oracleColumnsSql(String schema, String table) {
        SqlBuilder.ident(table);
        String base = "SELECT column_name AS name, data_type AS type, '' AS comment, column_id AS pos, "
                + " data_precision AS precision_, data_scale AS scale_ FROM all_tab_columns WHERE table_name='"
                + table.toUpperCase() + "'";
        if (schema != null && !schema.isEmpty()) {
            SqlBuilder.ident(schema);
            base += " AND owner='" + schema.toUpperCase() + "'";
        }
        return base + " ORDER BY column_id";
    }

    // ---------------- 执行助手 ----------------

    private static List<Map<String, Object>> query(DataSource pool, String sql) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                out.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("元数据查询失败：" + rootMsg(e), e);
        }
        return out;
    }

    /** SHOW TABLES（返回单列，统一包装为 {name}）。 */
    private static List<Map<String, Object>> querySimple(DataSource pool, String sql) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString(1));
                row.put("schema_name", null);
                row.put("comment", "");
                out.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("SHOW TABLES 失败：" + rootMsg(e), e);
        }
        return out;
    }

    /** DESCRIBE table（TDengine/Hive：第一列字段名，第二列类型）。 */
    private static List<Map<String, Object>> queryDescribe(DataSource pool, String table) {
        SqlBuilder.ident(table);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DESCRIBE `" + table + "`");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", rs.getString(1));
                row.put("type", rs.getString(2));
                row.put("comment", "");
                out.add(row);
            }
        } catch (SQLException e) {
            throw new RuntimeException("DESCRIBE 失败：" + rootMsg(e), e);
        }
        return out;
    }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
