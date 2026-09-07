package com.pharma.service.access.layer;

import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.adapter.IcebergAdapter;
import com.pharma.service.access.ingest.IcebergWriter;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.access.util.TargetDialect;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数仓分层路由：让「分层管理」里登记的层→数据源绑定真正决定数据落库位置。
 * <ul>
 *   <li>探查自动建模 / 模型建物理表 统一经 {@link #createIfAbsent}：无绑定或绑定不可用 →
 *       主库 StarRocks（原行为兜底）；绑定 iceberg → REST Catalog 建湖表（namespace=层编码，
 *       StarRocks 侧三段名可查）；绑定其余内部 JDBC 库（mysql/doris/clickhouse/pg 系…）→
 *       {@link TargetDialect} 方言在目标源建「层编码库」</li>
 *   <li>层命名规范前置校验：{@link #validateNaming} 按层 naming_pattern 拦截不合规表名（防患于事后巡检）</li>
 *   <li>层库一键初始化 {@link #initLayerDb}：在绑定数据源上 CREATE DATABASE/SCHEMA/NAMESPACE</li>
 * </ul>
 */
@Component
public class LayerRouter {

    /** 内部存储型数据源（与 DataSourceController.INTERNAL_TYPES 对齐）。 */
    public static final Set<String> INTERNAL_TYPES = Set.of("mysql", "starrocks", "doris", "clickhouse", "hive", "iceberg");

    /** 建表目标：MAIN=主库 StarRocks；JDBC=绑定的内部 JDBC 库；ICEBERG=湖（REST Catalog）。 */
    public record Target(String kind, long dsId, String dsName, String dsType, DataSourceDescriptor desc) {
        public static final Target MAIN = new Target("MAIN", 0, "主库 StarRocks", "starrocks", null);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private IcebergWriter icebergWriter;

    // ===================== 目标解析 =====================

    /** 解析层的建表目标：取第一个可加载的 NORMAL 内部绑定源；iceberg 优先于其它（湖是显式选择）。 */
    public Target resolve(String layerCode) {
        if (layerCode == null || layerCode.isBlank()) return Target.MAIN;
        List<Map<String, Object>> binds;
        try {
            binds = jdbc.queryForList(
                    "SELECT d.id, d.name, d.type FROM meta.gov_layer_datasource b " +
                            "JOIN meta.ing_datasource d ON d.id=b.datasource_id " +
                            "WHERE b.layer_code=? AND d.status='NORMAL' ORDER BY CASE d.type WHEN 'iceberg' THEN 0 ELSE 1 END, b.id",
                    layerCode);
        } catch (Exception e) {
            return Target.MAIN;
        }
        for (Map<String, Object> b : binds) {
            String type = str(b.get("type"));
            if (!INTERNAL_TYPES.contains(type)) continue;   // 外部源不作为数仓存储
            long id = lng(b.get("id"));
            try {
                DataSourceDescriptor ds = loader.load(id);
                return new Target("iceberg".equals(type) ? "ICEBERG" : "JDBC", id, str(b.get("name")), type, ds);
            } catch (Exception ignored) { /* 该绑定源加载失败，试下一个 */ }
        }
        return Target.MAIN;
    }

    /** 目标描述（日志/前端提示）：dwd（主库） / iceberg_catalog.dwd（湖表） / 演示源.dwd（mysql）。 */
    public static String describe(Target t, String layerCode) {
        if (t == null || t.kind().equals("MAIN")) return layerCode + "（主库 StarRocks）";
        if (t.kind().equals("ICEBERG")) return IcebergAdapter.SR_CATALOG + "." + layerCode + "（湖表）";
        return t.dsName() + "." + layerCode + "（" + t.dsType() + "）";
    }

    // ===================== 命名规范前置校验 =====================

    /** 层命名规范校验：合规 / 层未配置返回 null；违规返回错误提示（含建议名）。 */
    public String validateNaming(String layerCode, String table) {
        if (layerCode == null || layerCode.isBlank()) return null;
        String pattern;
        try {
            pattern = jdbc.queryForObject("SELECT naming_pattern FROM meta.gov_layer WHERE code=?", String.class, layerCode);
        } catch (Exception e) {
            return null;
        }
        if (pattern == null || pattern.isBlank()) return null;
        try {
            if (java.util.regex.Pattern.compile(pattern).matcher(table).find()) return null;
        } catch (Exception e) {
            return null;   // 坏正则不拦建表（巡检侧会报）
        }
        return "表名 " + table + " 不符合 " + layerCode + " 层命名规范 " + pattern + "（建议 " + suggest(layerCode, table) + "）";
    }

    /** 建议表名：层编码_原名（与命名巡检同规则）。 */
    public static String suggest(String layerCode, String table) { return layerCode + "_" + table; }

    // ===================== 建表 / 存在性 =====================

    /**
     * 统一建表入口：先命名校验（违规抛 IllegalArgumentException，调用方可跳过单表），
     * 再按层绑定路由。返回建到哪的描述；null=目标已存在未建。
     */
    public String createIfAbsent(String layerCode, String table, List<StarRocksDdlBuilder.ColumnDef> cols) {
        return createIfAbsent(layerCode, table, cols, null);
    }

    /** 同上，带主键（仅主库 StarRocks 路径生效：PRIMARY KEY 模型；湖表/JDBC 目标忽略）。 */
    public String createIfAbsent(String layerCode, String table, List<StarRocksDdlBuilder.ColumnDef> cols, String bizKey) {
        String bad = validateNaming(layerCode, table);
        if (bad != null) throw new IllegalArgumentException(bad);
        Target t = resolve(layerCode);
        StarRocksDdlBuilder.ident(layerCode);
        StarRocksDdlBuilder.ident(table);
        return switch (t.kind()) {
            case "ICEBERG" -> icebergWriter.createTable(t.desc(), layerCode, table, icebergCols(cols))
                    ? describe(t, layerCode) : null;
            case "JDBC" -> {
                if (targetExistsOn(t, layerCode, table)) yield null;
                String ddl = TargetDialect.forType(t.dsType()).createTable(layerCode, table, remapCols(t.dsType(), cols), null, false);
                new JdbcTemplate(registry.getPool(t.desc())).execute(ddl);
                yield describe(t, layerCode);
            }
            default -> {
                if (mainExists(layerCode, table)) yield null;
                jdbc.execute(StarRocksDdlBuilder.build(layerCode, table, cols, bizKey, bizKey != null && !bizKey.isBlank()));
                yield describe(t, layerCode);
            }
        };
    }

    /** 按层路由的存在性检查（探查左侧同名表置灰 / 建表幂等判断）。 */
    public boolean targetExists(String layerCode, String table) {
        String name = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
        Target t = resolve(layerCode);
        return switch (t.kind()) {
            case "ICEBERG" -> icebergTableExists(t.desc(), layerCode, name);
            case "JDBC" -> targetExistsOn(t, layerCode, name);
            default -> mainExists(layerCode, name);
        };
    }

    /** 主库（StarRocks）层库是否存在该表。 */
    public boolean mainExists(String layerCode, String table) {
        try {
            Long c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",
                    Long.class, layerCode, table);
            return c != null && c > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** JDBC 目标源上层库是否存在该表（mysql 系按 database、pg 系按 schema 查 information_schema）。 */
    private boolean targetExistsOn(Target t, String layerCode, String table) {
        try {
            Long c = new JdbcTemplate(registry.getPool(t.desc())).queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",
                    Long.class, layerCode, table);
            return c != null && c > 0;
        } catch (Exception e) {
            return false;   // 方言无 information_schema 时当不存在，建表再报
        }
    }

    private boolean icebergTableExists(DataSourceDescriptor ds, String ns, String table) {
        try (RESTCatalog catalog = IcebergAdapter.catalog(ds)) {
            return catalog.tableExists(TableIdentifier.of(Namespace.of(ns), table));
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== 层库初始化 =====================

    /**
     * 在层的绑定数据源上初始化「层编码库」（无绑定 → 主库）。
     * mysql/starrocks/doris/clickhouse/hive：CREATE DATABASE IF NOT EXISTS；pg 系：CREATE SCHEMA
     * IF NOT EXISTS（建在连接默认库内）；iceberg：createNamespace。返回描述。
     */
    public String initLayerDb(String layerCode) {
        StarRocksDdlBuilder.ident(layerCode);
        Target t = resolve(layerCode);
        switch (t.kind()) {
            case "ICEBERG" -> {
                try (RESTCatalog catalog = IcebergAdapter.catalog(t.desc())) {
                    catalog.createNamespace(Namespace.of(layerCode));
                } catch (Exception ignored) { /* 已存在 */ }
                return IcebergAdapter.SR_CATALOG + "." + layerCode + "（湖命名空间）";
            }
            case "JDBC" -> {
                JdbcTemplate target = new JdbcTemplate(registry.getPool(t.desc()));
                boolean pgFamily = t.dsType().startsWith("postgres") || t.dsType().equals("greenplum")
                        || t.dsType().equals("opengauss") || t.dsType().equals("kingbase");
                String sql = pgFamily
                        ? "CREATE SCHEMA IF NOT EXISTS " + TargetDialect.forType(t.dsType()).quote(layerCode)
                        : "CREATE DATABASE IF NOT EXISTS " + TargetDialect.forType(t.dsType()).quote(layerCode);
                try {
                    target.execute(sql);
                } catch (Exception e) {
                    // oracle/sqlserver 等无 IF NOT EXISTS：already exists 视为成功，其余抛出
                    if (!String.valueOf(e.getMessage()).toLowerCase().contains("exist")) {
                        throw new IllegalArgumentException("初始化层库失败：" + rootMsg(e));
                    }
                }
                return describe(t, layerCode);
            }
            default -> {
                jdbc.execute("CREATE DATABASE IF NOT EXISTS `" + layerCode + "`");
                return layerCode + "（主库 StarRocks）";
            }
        }
    }

    // -------- 助手：列定义转换 --------

    /** StarRocks 列定义 → 湖表列定义 [{name,type,comment}]（类型经 modelIcebergType 映射）。 */
    private static List<Map<String, Object>> icebergCols(List<StarRocksDdlBuilder.ColumnDef> cols) {
        List<Map<String, Object>> out = new ArrayList<>(cols.size());
        for (StarRocksDdlBuilder.ColumnDef c : cols) {
            out.add(Map.of("name", c.name, "type", c.type));
        }
        return out;
    }

    /**
     * StarRocks 类型列定义 → 目标方言列定义（探查/模型侧的列类型已是 SR 文本，
     * ClickHouse/MySQL/PG 系不认识 STRING 等，需按目标重映射）。
     */
    private static List<StarRocksDdlBuilder.ColumnDef> remapCols(String dsType, List<StarRocksDdlBuilder.ColumnDef> cols) {
        List<StarRocksDdlBuilder.ColumnDef> out = new ArrayList<>(cols.size());
        for (StarRocksDdlBuilder.ColumnDef c : cols) {
            out.add(new StarRocksDdlBuilder.ColumnDef(c.name, remapType(dsType, c.type)));
        }
        return out;
    }

    /** SR 类型文本 → 目标方言类型文本（目标同为 starrocks/doris 时原样）。 */
    static String remapType(String dsType, String t) {
        String u = t == null ? "STRING" : t.trim().toUpperCase();
        if (u.isEmpty()) u = "STRING";
        switch (dsType) {
            case "starrocks": case "doris": case "hive":
                return t;   // SR 类型文本直接可用；hive 接 STRING/BIGINT/INT 等
            case "clickhouse":
                return switch (u) {
                    case "STRING" -> "String";
                    case "DATETIME" -> "DateTime";
                    case "DATE" -> "Date";
                    case "BOOLEAN" -> "UInt8";
                    case "TINYINT" -> "Int8";
                    case "SMALLINT" -> "Int16";
                    case "INT" -> "Int32";
                    case "BIGINT" -> "Int64";
                    case "FLOAT" -> "Float32";
                    case "DOUBLE" -> "Float64";
                    default -> u.startsWith("DECIMAL") ? "Decimal(18,4)" : "String";
                };
            case "mysql":
                return switch (u) {
                    case "STRING" -> "TEXT";
                    case "BOOLEAN" -> "TINYINT(1)";
                    default -> t;   // VARCHAR/BIGINT/DECIMAL/DATE/DATETIME 均兼容
                };
            default:   // pg 系（greenplum/opengauss/kingbase 走 postgresql 驱动）
                return switch (u) {
                    case "STRING" -> "TEXT";
                    case "DATETIME" -> "TIMESTAMP";
                    case "TINYINT", "SMALLINT" -> "SMALLINT";
                    case "INT" -> "INTEGER";
                    default -> t;
                };
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
