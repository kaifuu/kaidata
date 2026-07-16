package com.pharma.service.access.util;

import java.util.List;
import java.util.function.Function;

/**
 * 目标库方言抽象：离线接入把数据写入不同目标库时，标识符引用 / 建表 DDL / 类型映射的差异集中在此。
 * <ul>
 *   <li>{@link StarRocksDialect}（starrocks/doris）：主键模型去重，委托 {@link StarRocksDdlBuilder}</li>
 *   <li>{@link ClickHouseDialect}：MergeTree 引擎，ORDER BY tuple()</li>
 *   <li>{@link AnsiDialect}：通用 ANSI 建表，覆盖 mysql/postgresql/greenplum/opengauss/sqlserver/oracle</li>
 * </ul>
 * 全方言统一用 {@link StarRocksDdlBuilder#ident} 校验标识符（仅 [a-zA-Z0-9_]）防注入。
 */
public interface TargetDialect {

    /** 标识符引用：mysql/clickhouse→`x`，pg/oracle→"x"，sqlserver→[x]。 */
    String quote(String ident);

    /** 限定名 quote(db)+"."+quote(table)；db 为空时仅 quote(table)（走连接默认 schema）。 */
    default String qualify(String db, String table) {
        return (db == null || db.isEmpty()) ? quote(table) : quote(db) + "." + quote(table);
    }

    /** 按本方言映射 JDBC 列类型（基于 {@link TypeMapper#toStarRocks} 再做方言修正）。 */
    String mapType(int jdbcType, String typeName, int precision, int scale);

    /** 生成 CREATE TABLE IF NOT EXISTS；keyCol/incremental 仅 starrocks/doris 用（PRIMARY KEY 模型）。 */
    String createTable(String db, String table, List<StarRocksDdlBuilder.ColumnDef> cols, String keyCol, boolean incremental);

    /** FULL 模式清表。 */
    default String clearTable(String db, String table) {
        return "TRUNCATE TABLE " + qualify(db, table);
    }

    /** 是否需要把键列重排到列前缀（StarRocks 硬约束）。 */
    boolean reorderKeyPrefix();

    static TargetDialect forType(String t) {
        if (t == null) return AnsiDialect.MYSQL;
        switch (t) {
            case "starrocks":
            case "doris":       return StarRocksDialect.INSTANCE;
            case "clickhouse":  return ClickHouseDialect.INSTANCE;
            case "postgresql":
            case "greenplum":
            case "opengauss":   return AnsiDialect.PG;
            case "sqlserver":   return AnsiDialect.SQLSERVER;
            case "oracle":      return AnsiDialect.ORACLE;
            default:            return AnsiDialect.MYSQL;
        }
    }

    // ===================== StarRocks / Doris =====================
    // 委托 StarRocksDdlBuilder（DUPLICATE/PRIMARY KEY + DISTRIBUTED BY），增量可走主键模型去重。
    class StarRocksDialect implements TargetDialect {
        static final StarRocksDialect INSTANCE = new StarRocksDialect();
        @Override public String quote(String id) { StarRocksDdlBuilder.ident(id); return "`" + id + "`"; }
        @Override public String mapType(int j, String n, int p, int s) { return TypeMapper.toStarRocks(j, n, p, s); }
        @Override public String createTable(String db, String table, List<StarRocksDdlBuilder.ColumnDef> cols, String keyCol, boolean inc) {
            return StarRocksDdlBuilder.build(db, table, cols, keyCol, inc);
        }
        @Override public boolean reorderKeyPrefix() { return true; }
    }

    // ===================== ClickHouse =====================
    /** MergeTree 必须带 ORDER BY，全量场景用 ORDER BY tuple()（无排序键）。 */
    class ClickHouseDialect implements TargetDialect {
        static final ClickHouseDialect INSTANCE = new ClickHouseDialect();
        @Override public String quote(String id) { StarRocksDdlBuilder.ident(id); return "`" + id + "`"; }
        @Override public String mapType(int j, String n, int p, int s) {
            String t = TypeMapper.toStarRocks(j, n, p, s);
            switch (t) {
                case "STRING": return "String";
                case "DATETIME": return "DateTime";
                case "DATE": return "Date";
                case "BOOLEAN": return "UInt8";
                case "TINYINT": return "Int8";
                case "SMALLINT": return "Int16";
                case "INT": return "Int32";
                case "BIGINT": return "Int64";
                case "FLOAT": return "Float32";
                case "DOUBLE": return "Float64";
                default: return t.startsWith("DECIMAL") ? "Decimal(18,4)" : "String";
            }
        }
        @Override public String createTable(String db, String table, List<StarRocksDdlBuilder.ColumnDef> cols, String keyCol, boolean inc) {
            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(qualify(db, table)).append(" (");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(cols.get(i).name)).append(" ").append(cols.get(i).type);
            }
            return sb.append(") ENGINE = MergeTree() ORDER BY tuple()").toString();
        }
        @Override public boolean reorderKeyPrefix() { return false; }
    }

    // ===================== ANSI（mysql/pg/oracle/sqlserver...） =====================
    /** 通用建表，不带 KEY/DISTRIBUTED 子句；非 StarRocks 目标仅全量，故忽略 keyCol/incremental。 */
    class AnsiDialect implements TargetDialect {
        static final AnsiDialect MYSQL = new AnsiDialect("`", "`", t -> t.equals("STRING") ? "LONGTEXT" : t);
        static final AnsiDialect PG = new AnsiDialect("\"", "\"", t ->
                t.equals("STRING") ? "TEXT" : t.equals("DATETIME") ? "TIMESTAMP" : t);
        static final AnsiDialect SQLSERVER = new AnsiDialect("[", "]", t ->
                t.equals("STRING") ? "NVARCHAR(MAX)" : t.equals("DATETIME") ? "DATETIME2" : t.equals("BOOLEAN") ? "BIT" : t);
        static final AnsiDialect ORACLE = new AnsiDialect("\"", "\"", t ->
                t.equals("STRING") ? "CLOB" : t.equals("DATETIME") ? "TIMESTAMP" : t.equals("BOOLEAN") ? "NUMBER(1)" : t);

        private final String lq, rq;
        private final Function<String, String> remap;
        AnsiDialect(String lq, String rq, Function<String, String> remap) {
            this.lq = lq; this.rq = rq; this.remap = remap;
        }
        @Override public String quote(String id) { StarRocksDdlBuilder.ident(id); return lq + id + rq; }
        @Override public String mapType(int j, String n, int p, int s) {
            return remap.apply(TypeMapper.toStarRocks(j, n, p, s));
        }
        @Override public String createTable(String db, String table, List<StarRocksDdlBuilder.ColumnDef> cols, String keyCol, boolean inc) {
            StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(qualify(db, table)).append(" (");
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(quote(cols.get(i).name)).append(" ").append(cols.get(i).type);
            }
            return sb.append(")").toString();
        }
        @Override public boolean reorderKeyPrefix() { return false; }
    }
}
