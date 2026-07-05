package com.pharma.service.access.util;

/**
 * 按方言构造元数据查询与抽取 SQL 的助手。
 * <p>
 * 默认基于 information_schema（MySQL/StarRocks/Doris/PostgreSQL/Greenplum/OpenGauss/ClickHouse 等支持）；
 * Oracle/TDengine/Hive 等在各自 adapter 中覆写。
 */
public final class SqlBuilder {

    private SqlBuilder() {}

    /** 校验标识符（允许库.表 形式时由调用方拆分后再校验每段）。 */
    public static void ident(String s) {
        if (s == null || !s.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("非法标识符: " + s);
        }
    }

    /** 拆分 schema.table 或 table，返回 [schema, table]，schema 可能为 null。 */
    public static String[] splitTable(String t) {
        if (t == null) return new String[]{null, null};
        int i = t.lastIndexOf('.');
        if (i < 0) return new String[]{null, t};
        return new String[]{t.substring(0, i), t.substring(i + 1)};
    }

    public static String quote(String s) {
        ident(s);
        return "`" + s + "`";
    }

    /** 列源库的表（information_schema.tables 标准查询）。 */
    public static String listTablesSql(String schema) {
        String sql = "SELECT TABLE_NAME AS name, TABLE_SCHEMA AS schema_name, " +
                "COALESCE(TABLE_COMMENT,'') AS comment " +
                "FROM information_schema.tables WHERE TABLE_TYPE='BASE TABLE'";
        if (schema != null && !schema.isEmpty()) {
            ident(schema);
            sql += " AND TABLE_SCHEMA='" + schema + "'";
        }
        return sql + " ORDER BY TABLE_SCHEMA, TABLE_NAME";
    }

    /** 描述表字段（information_schema.columns 标准查询）。 */
    public static String describeSql(String schema, String table) {
        String tbl = table == null ? "" : table;
        ident(tbl);
        String sql = "SELECT COLUMN_NAME AS name, DATA_TYPE AS type, " +
                "COALESCE(COLUMN_COMMENT,'') AS comment, ORDINAL_POSITION AS pos, " +
                "NUMERIC_PRECISION AS precision_, NUMERIC_SCALE AS scale_ " +
                "FROM information_schema.columns WHERE TABLE_NAME='" + tbl + "'";
        if (schema != null && !schema.isEmpty()) {
            ident(schema);
            sql += " AND TABLE_SCHEMA='" + schema + "'";
        }
        return sql + " ORDER BY ORDINAL_POSITION";
    }

    /** 增量抽取 WHERE：WHERE incCol > 'lastValue'。 */
    public static String incrementWhere(String incCol, String lastValue) {
        ident(incCol);
        String v = lastValue == null ? "" : lastValue.replace("'", "''");
        return " WHERE `" + incCol + "` > '" + v + "'";
    }
}
