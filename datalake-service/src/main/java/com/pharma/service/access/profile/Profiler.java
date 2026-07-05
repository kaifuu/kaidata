package com.pharma.service.access.profile;

import com.pharma.service.access.util.TypeMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段探查：按勾选项统计空值 / 唯一值 / 数值分布（min/max，仅数值列）。
 * 每列独立查询（标准 SQL，各库通用）；大表 COUNT(DISTINCT) 较慢，MVP 可接受。
 */
public final class Profiler {

    private Profiler() {}

    /**
     * @param cols    describeTable 原始字段 [{name, type}]
     * @param options 列名 → 勾选的探查项 ["null","unique","dist"]（unique/distinct 等价）
     */
    public static Map<String, Object> profile(DataSource pool, String table,
                                              List<Map<String, Object>> cols,
                                              Map<String, List<String>> options) throws Exception {
        validateTable(table);
        Map<String, Object> stats = new LinkedHashMap<>();
        long total = queryLong(pool, "SELECT COUNT(*) FROM " + table);
        stats.put("_total", total);
        if (options == null || options.isEmpty()) return stats;
        for (Map<String, Object> c : cols) {
            String name = str(c.get("name"));
            String type = str(c.get("type"));
            List<String> opt = options.get(name);
            if (opt == null || opt.isEmpty()) continue;
            validateCol(name);
            Map<String, Object> cs = new LinkedHashMap<>();
            for (String o : opt) {
                switch (o) {
                    case "null":
                        cs.put("null_count", queryLong(pool, "SELECT COUNT(*) FROM " + table + " WHERE " + name + " IS NULL"));
                        break;
                    case "unique":
                    case "distinct":
                        cs.put("distinct_count", queryLong(pool, "SELECT COUNT(DISTINCT " + name + ") FROM " + table));
                        break;
                    case "dist":
                        if (isNumeric(type)) {
                            Map<String, Object> row = queryRow(pool,
                                    "SELECT MIN(" + name + ") AS mn, MAX(" + name + ") AS mx FROM " + table);
                            cs.put("min", row.get("mn"));
                            cs.put("max", row.get("mx"));
                        }
                        break;
                }
            }
            if (!cs.isEmpty()) stats.put(name, cs);
        }
        return stats;
    }

    public static boolean isNumeric(String jdbcType) {
        String sr = TypeMapper.mapByName(jdbcType).toUpperCase();
        return sr.contains("INT") || sr.contains("DOUBLE") || sr.contains("DECIMAL")
                || sr.contains("FLOAT") || sr.contains("REAL") || sr.contains("NUMERIC");
    }

    // -------- 助手 --------

    private static long queryLong(DataSource pool, String sql) throws Exception {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static Map<String, Object> queryRow(DataSource pool, String sql) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
            }
        }
        return row;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static void validateTable(String t) {
        if (t == null || !t.matches("[a-zA-Z0-9_.]+")) throw new IllegalArgumentException("非法表名: " + t);
    }

    private static void validateCol(String c) {
        if (c == null || !c.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("非法字段名: " + c);
    }
}
