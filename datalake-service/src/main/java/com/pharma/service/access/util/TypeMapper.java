package com.pharma.service.access.util;

import java.sql.Types;

/**
 * JDBC 列类型 → StarRocks 列类型映射。
 * <p>
 * 先按 jdbcType 标准映射，typeName 兜底（部分驱动 jdbcType 不准，如 ClickHouse/TDengine）。
 */
public final class TypeMapper {

    private TypeMapper() {}

    public static String toStarRocks(int jdbcType, String typeName, int precision, int scale) {
        switch (jdbcType) {
            case Types.BIT:
            case Types.BOOLEAN:
                return "BOOLEAN";
            case Types.TINYINT:
                return "TINYINT";
            case Types.SMALLINT:
                return "SMALLINT";
            case Types.INTEGER:
                return "INT";
            case Types.BIGINT:
                return "BIGINT";
            case Types.FLOAT:
            case Types.REAL:
                return "FLOAT";
            case Types.DOUBLE:
                return "DOUBLE";
            case Types.NUMERIC:
            case Types.DECIMAL:
                if (scale > 0) return "DECIMAL(" + Math.max(precision, scale) + "," + scale + ")";
                return precision > 0 ? "DECIMAL(" + precision + ",0)" : "BIGINT";
            case Types.CHAR:
            case Types.NCHAR:
                return "CHAR(" + Math.max(precision, 1) + ")";
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return (precision > 0 && precision < 65533) ? "VARCHAR(" + precision + ")" : "STRING";
            case Types.DATE:
                return "DATE";
            case Types.TIME:
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
            case Types.TIME_WITH_TIMEZONE:
                return "DATETIME";
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.CLOB:
            case Types.NCLOB:
                return "STRING";
            default:
                return mapByName(typeName);
        }
    }

    /** 按 typeName 兜底（当 jdbcType 为 OTHER 或驱动未填充时）。 */
    public static String mapByName(String n) {
        if (n == null) return "STRING";
        String u = n.toLowerCase();
        if (u.contains("bool")) return "BOOLEAN";
        if (u.contains("tinyint")) return "TINYINT";
        if (u.contains("smallint") || u.contains("int16")) return "SMALLINT";
        if (u.matches(".*\\b(int|int32|integer|serial)\\b.*")) return "INT";
        if (u.contains("bigint") || u.contains("int64") || u.contains("long")) return "BIGINT";
        if (u.contains("float") || u.contains("real")) return "FLOAT";
        if (u.contains("double")) return "DOUBLE";
        if (u.contains("datetime") || u.contains("timestamp")) return "DATETIME";
        if (u.contains("date")) return "DATE";
        if (u.contains("time")) return "DATETIME";
        if (u.contains("decimal") || u.contains("numeric")) return "DECIMAL(18,4)";
        if (u.contains("text") || u.contains("string") || u.contains("varchar")) return "STRING";
        return "STRING";
    }
}
