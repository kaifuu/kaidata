package com.pharma.service.access.develop.job;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Flink 图形化作业（job_type=flink_dag）：把 DAG（source/operator/sink）翻译成 FlinkSQL，交 FlinkSqlExecutor 执行。
 * <p>拓扑排序后：source/sink 生成 CREATE TABLE...WITH(connector)；operator 生成 CREATE VIEW
 * （filter=WHERE / aggregate=GROUP BY / select / sort=ORDER BY / value_map=CASE / calc=算术列 / dedup=DISTINCT / udf / join）；
 * sink 末尾生成 INSERT INTO...SELECT。
 * <p>table 源/汇的 JDBC 连接信息取自节点绑定的数据源；sink 列取字段映射的 target（详见 buildCreateTable）。
 */
@Component
public class FlinkDagExecutor extends AbstractHttpExecutor {

    /** Flink jdbc 连接器能接的关系库类型（驱动只有 MySQL 系，其余类型能生成 URL 但需另放驱动）。 */
    private static final Set<String> JDBC_TYPES = Set.of("mysql", "starrocks", "doris", "postgresql", "greenplum",
            "kingbase", "opengauss", "clickhouse", "sqlserver", "oracle", "hive", "tdengine");

    /** 日志里抹掉数据源口令——翻译后的 SQL 会落进 dev_offline_run.log_text，不能把明文密码带进去。 */
    private static final Pattern PWD_IN_SQL = Pattern.compile("'password'\\s*=\\s*'(?:[^']|'')*'");

    @Autowired private FlinkSqlExecutor sqlExecutor;
    @Autowired private DataSourceLoader dsLoader;
    @Autowired private DataSourceAdapterRegistry registry;

    @Override public String jobType() { return "flink_dag"; }

    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        Map<String, Object> dag = parseJson(str(task.get("dag_json")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dag.getOrDefault("nodes", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) dag.getOrDefault("edges", Collections.emptyList());
        if (nodes.isEmpty()) throw new RuntimeException("DAG 没有节点");

        List<Map<String, Object>> order = DagGraph.topoSort(nodes, edges);
        Map<String, String> alias = new HashMap<>();   // nodeId → flink 表/视图名
        Map<String, List<String>> outCols = new HashMap<>();   // 视图名 → 输出列；null=形状不定，sink 校验时跳过
        StringBuilder sql = new StringBuilder();

        for (Map<String, Object> node : order) {
            String id = str(node.get("id"));
            String name = "flink_" + id.replaceAll("[^a-zA-Z0-9]", "_");
            alias.put(id, name);
            String type = str(node.get("type"));
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) node.getOrDefault("data", Map.of());
            String kind = str(data.get("kind"));
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) data.getOrDefault("config", Map.of());

            if ("source".equals(type) || "sink".equals(type)) {
                // Flink 连接器仅支持 table / kafka 系列；文件/REST/insert_update 等无对应连接器，显式报错，
                // 与前端 support 矩阵（KETTLE_ONLY）对齐——避免生成 table-name 为空的废表静默失败。
                if (!isFlinkSupportedEndpoint(kind))
                    throw new RuntimeException("算子[" + kind + "]的 Flink 连接器尚未实现（FlinkSQL 仅支持 table / kafka_input / kafka_output）");
                boolean kafka = isKafka(kind);
                sql.append(buildCreateTable(name, kafka, cfg, "sink".equals(type), kafka ? null : loadDs(cfg))).append(";\n");
                // sink 表只作写入端，不参与下游列校验
                if ("source".equals(type)) outCols.put(name, fieldList(cfg.get("fields")));
            } else if ("operator".equals(type)) {
                String upstream = DagGraph.findUpstream(edges, id, alias);
                List<String> upstreams = DagGraph.findUpstreams(edges, id, alias);
                sql.append(buildOperatorView(name, kind, cfg, upstream, upstreams)).append(";\n");
                outCols.put(name, derivedCols(kind, cfg, upstream == null ? null : outCols.get(upstream)));
            }
        }
        // sink 的 INSERT INTO ... SELECT（有字段映射则写显式列，否则 SELECT *）
        for (Map<String, Object> node : nodes) {
            if (!"sink".equals(str(node.get("type")))) continue;
            String id = str(node.get("id"));
            String upstream = DagGraph.findUpstream(edges, id, alias);
            if (upstream == null) continue;
            String sinkName = alias.get(id);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) node.getOrDefault("data", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) data.getOrDefault("config", Map.of());
            if (isKafka(str(data.get("kind")))) {
                sql.append("INSERT INTO ").append(sinkName).append(" SELECT * FROM ").append(upstream).append(";\n");
                continue;
            }
            List<String> targets = mappingSide(cfg, "target");
            List<String> sources = mappingSide(cfg, "source");
            boolean mapped = !targets.isEmpty() && targets.size() == sources.size();
            if (mapped) {
                // 映射列对不上上游时提前报错：Flink 原生报的是 "Column 'x' not found"，
                // 定位不到是哪个节点，这里直接点名节点与上游实际列
                List<String> up = outCols.get(upstream);
                if (up != null) {
                    List<String> missing = new ArrayList<>();
                    for (String c : sources) if (!up.contains(c)) missing.add(c);
                    if (!missing.isEmpty())
                        throw new RuntimeException("表输出[" + str(data.get("label")) + "]的字段映射引用了上游不存在的列 "
                                + missing + "；上游实际列为 " + up + "，请把右侧「字段映射」的源字段与上游对齐");
                }
                sql.append("INSERT INTO ").append(sinkName).append(" (").append(String.join(", ", targets)).append(")")
                        .append(" SELECT ").append(String.join(", ", sources)).append(" FROM ").append(upstream).append(";\n");
            } else {
                sql.append("INSERT INTO ").append(sinkName).append(" SELECT * FROM ").append(upstream).append(";\n");
            }
        }
        log.append("DAG 翻译为 FlinkSQL:\n").append(PWD_IN_SQL.matcher(sql).replaceAll("'password'='***'")).append("\n");

        Map<String, Object> translated = new LinkedHashMap<>(task);
        translated.put("sql_content", sql.toString());
        return sqlExecutor.execute(taskId, translated, log);
    }

    /** Flink 真正支持的 source/sink kind：table（jdbc）+ kafka 系列。其余（csv/excel/json/xml/text/rest/generate/insert_update）无连接器。 */
    private static boolean isFlinkSupportedEndpoint(String kind) {
        return "table".equalsIgnoreCase(str(kind)) || isKafka(kind);
    }

    private static boolean isKafka(String kind) {
        if (kind == null) return false;
        String k = kind.toLowerCase();
        return "kafka".equals(k) || "kafka_input".equals(k) || "kafka_output".equals(k);
    }

    /**
     * source/sink 的 CREATE TABLE。
     * <p>列：source 取 fields；sink 取字段映射的 target（table sink 的 fields 通常为空，旧实现退化成
     * 单列 {@code data STRING}，INSERT 时列数对不上报 "Column types of query result and sink do not match"）。
     * <p>JDBC 参数：url/username/password 取自节点绑定的数据源，不再用占位的 {@code jdbc:mysql://host:9030/db}。
     */
    private String buildCreateTable(String name, boolean kafka, Map<String, Object> cfg, boolean isSink, DataSourceDescriptor ds) {
        List<String[]> cols;
        if (kafka) {
            cols = strCols(fieldList(cfg.get("fields")));
        } else {
            cols = describeCols(ds, cfg, isSink ? sinkCols(cfg) : fieldList(cfg.get("fields")));
        }
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(name).append(" (");
        if (cols.isEmpty()) sb.append("data STRING");
        else {
            List<String> defs = new ArrayList<>();
            for (String[] c : cols) defs.add(c[0] + " " + c[1]);
            sb.append(String.join(", ", defs));
        }
        sb.append(") WITH ('connector'='").append(kafka ? "kafka" : "jdbc").append("'");
        if (kafka) {
            String topic = str(cfg.get("topic"));
            if (topic.isEmpty()) throw new RuntimeException("kafka 节点未配置 topic");
            sb.append(", 'topic'='").append(topic)
                    .append("', 'properties.bootstrap.servers'='pharma-kafka:9092', 'format'='json'");
        } else {
            String ref = tableRef(cfg, ds.dbName);
            if (ref.isEmpty()) throw new RuntimeException("表节点未配置表名");
            // quote() 自带外层单引号，这里不能再补一对（否则拼成 ''root''）
            sb.append(", 'url'=").append(quote(jdbcUrl(ds)))
                    .append(", 'table-name'=").append(quote(ref))
                    .append(", 'username'=").append(quote(ds.username))
                    .append(", 'password'=").append(quote(ds.password));
        }
        return sb.append(")").toString();
    }

    /** 无类型信息时的兜底：全部按 STRING 声明。 */
    private static List<String[]> strCols(List<String> names) {
        List<String[]> out = new ArrayList<>();
        for (String n : names) out.add(new String[]{n, "STRING"});
        return out;
    }

    /**
     * 取表的真实列类型拼 DDL 列定义。
     * <p>DAG 节点只存列名不存类型（前端 fields 是纯名字数组），一律声明 STRING 会在执行期炸：
     * JDBC 源读 BIGINT 报 {@code Long cannot be cast to String}，写库也会因类型不符失败。
     * <p>列名给定时按给定顺序取类型（查不到的列退化为 STRING）；列名为空时用整表结构，
     * 避免 sink 未配字段映射就退化成单列 data 导致 INSERT 列数不符。
     */
    private List<String[]> describeCols(DataSourceDescriptor ds, Map<String, Object> cfg, List<String> want) {
        Map<String, String> typeOf = new LinkedHashMap<>();
        try {
            DataSourceAdapter a = registry.adapter(ds.type);
            if (a != null)
                for (Map<String, Object> c : a.describeTable(registry.getPool(ds), str(cfg.get("schemaName")), str(cfg.get("tableName")))) {
                    String n = str(c.get("name"));
                    if (!n.isEmpty())
                        typeOf.putIfAbsent(n.toLowerCase(), flinkType(str(c.get("type")), c.get("precision_"), c.get("scale_")));
                }
        } catch (Exception ignored) {
            // 表不存在/无权限/驱动缺失：退回 STRING，行为与旧实现一致，不因取类型失败整个作业起不来
        }
        if (want.isEmpty()) {
            List<String[]> out = new ArrayList<>();
            for (Map.Entry<String, String> e : typeOf.entrySet()) out.add(new String[]{e.getKey(), e.getValue()});
            return out;
        }
        List<String[]> out = new ArrayList<>();
        for (String w : want) out.add(new String[]{w, typeOf.getOrDefault(w.toLowerCase(), "STRING")});
        return out;
    }

    /** 库类型名 → FlinkSQL 类型（带参形式先剥参数；未覆盖的一律 STRING）。 */
    private static String flinkType(String dbType, Object precision, Object scale) {
        String t = dbType == null ? "" : dbType.toLowerCase().trim();
        int p = t.indexOf('(');
        if (p > 0) t = t.substring(0, p).trim();
        switch (t) {
            case "tinyint": return "TINYINT";
            case "smallint": case "int2": return "SMALLINT";
            case "int": case "integer": case "int4": case "mediumint": return "INT";
            case "bigint": case "int8": return "BIGINT";
            case "float": case "real": return "FLOAT";
            case "double": case "double precision": case "float8": return "DOUBLE";
            case "decimal": case "numeric": case "number": {
                int pc = intOr(precision, 0), sc = intOr(scale, 0);
                return pc > 0 ? "DECIMAL(" + pc + "," + sc + ")" : "DECIMAL(38,18)";
            }
            case "boolean": case "bool": return "BOOLEAN";
            case "date": return "DATE";
            case "timestamp": case "datetime": return "TIMESTAMP(3)";
            case "time": return "TIME";
            case "binary": case "varbinary": case "blob": case "bytea": return "BYTES";
            default: return "STRING";   // char/varchar/text/json/enum 等
        }
    }

    /** 按节点绑定的数据源拼 Flink JDBC 连接串（容器内 localhost 指容器自己，须改写为宿主地址）。 */
    private static String jdbcUrl(DataSourceDescriptor d) {
        String host = d.host;
        if (host.isEmpty() || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) host = "host.docker.internal";
        String db = d.dbName;
        String base = switch (d.type) {
            case "postgresql", "greenplum", "kingbase", "opengauss" -> "jdbc:postgresql";
            case "clickhouse" -> "jdbc:clickhouse";
            case "sqlserver" -> "jdbc:sqlserver";
            case "oracle" -> "jdbc:oracle:thin:@";
            case "hive" -> "jdbc:hive2";
            case "tdengine" -> "jdbc:TAOS-RS";
            default -> "jdbc:mysql";                       // mysql / starrocks / doris
        };
        int port = d.port > 0 ? d.port : 3306;
        if ("sqlserver".equals(d.type)) return base + "://" + host + ":" + port + ";databaseName=" + db;
        if ("oracle".equals(d.type)) return base + host + ":" + port + ":" + db;
        return base + "://" + host + ":" + port + "/" + db;
    }

    /** 读取表节点绑定的数据源，顺带拦住非关系库（Flink 的 jdbc 连接器接不了湖/消息/文件源）。 */
    private DataSourceDescriptor loadDs(Map<String, Object> cfg) {
        long dsId = DataSourceDescriptor.lng(cfg.get("datasourceId"), 0);
        if (dsId <= 0) throw new RuntimeException("表节点未绑定数据源，请在右侧属性面板选择数据源");
        DataSourceDescriptor d;
        try {
            d = dsLoader.load(dsId);
        } catch (Exception e) {
            throw new RuntimeException("数据源 id=" + dsId + " 读取失败: " + e.getMessage());
        }
        if (!JDBC_TYPES.contains(d.type))
            throw new RuntimeException("表节点绑定的数据源[" + d.name + "]类型为 " + d.type
                    + "，Flink 的 jdbc 连接器只接关系库（StarRocks/MySQL/Doris/PG 等）；"
                    + "要读 Iceberg 湖表请改绑 StarRocks 数据源，表名写成 iceberg_catalog.<库>.<表>");
        return d;
    }

    /**
     * 表引用。JDBC url 里已带数据源的库名，schemaName 与之一致时只取表名——否则会拼成
     * {@code ods.ods.dem_user}（MySQL 连接器把 url 的 db 与 table-name 直接相连）。
     * 跨库/三段名（如 iceberg_catalog.demo.dem_user）与库名不同，原样保留。
     */
    private static String tableRef(Map<String, Object> cfg, String dbName) {
        String schema = str(cfg.get("schemaName")), table = str(cfg.get("tableName"));
        if (table.isEmpty()) return "";
        if (schema.isEmpty() || schema.equalsIgnoreCase(dbName)) return table;
        return schema + "." + table;
    }

    /** sink 列：字段映射成对时用 target，否则退化为 fields（再空则 buildCreateTable 兜底成单列 data）。 */
    private static List<String> sinkCols(Map<String, Object> cfg) {
        List<String> t = mappingSide(cfg, "target"), s = mappingSide(cfg, "source");
        return !t.isEmpty() && t.size() == s.size() ? t : fieldList(cfg.get("fields"));
    }

    /** 取字段映射（fieldMapping=[{source,target}]）某一侧的列名，保序去重。 */
    private static List<String> mappingSide(Map<String, Object> cfg, String key) {
        List<String> out = new ArrayList<>();
        if (cfg.get("fieldMapping") instanceof List<?> l)
            for (Object it : l)
                if (it instanceof Map<?, ?> m) {
                    String v = str(m.get(key));
                    if (!v.isEmpty() && !out.contains(v)) out.add(v);
                }
        return out;
    }

    /**
     * 算子输出列，仅供 sink 映射校验；形状拿不准就返回 null（跳过校验，交回 Flink 自身报错）。
     * <p>覆盖：列不变的过滤/校验类、取字段的 select/dedup、在 {@code *} 后追加派生列的各类算子。
     */
    private static List<String> derivedCols(String kind, Map<String, Object> cfg, List<String> up) {
        if (up == null) return null;
        String k = kind == null ? "" : kind.toLowerCase();
        switch (k) {
            case "filter", "sort", "sampling", "data_validate", "num_range",
                 "url_check", "id_check", "regex_check":
                return up;
            case "select", "dedup": {
                List<String> fs = fieldList(cfg.get("fields"));
                return fs.isEmpty() ? up : fs;
            }
            case "value_map": return appendCol(up, colName(cfg) + "_mapped");
            case "string_replace": return appendCol(up, colName(cfg) + "_repl");
            case "string_ops": return appendCol(up, colName(cfg) + "_ops");
            case "split_field": return appendCol(up, colName(cfg) + "_split");
            case "string_to_date": return appendCol(up, colName(cfg) + "_dt");
            case "null_check": return str(cfg.get("defaultVal")).isEmpty() ? up : appendCol(up, colName(cfg) + "_filled");
            case "mask_partial", "mask_delete": return appendCol(up, colName(cfg) + "_masked");
            case "switch_case": return appendCol(up, "branch");
            case "calc": return str(cfg.get("exprs")).isEmpty() && str(cfg.get("expression")).isEmpty() ? up : null;
            default: return null;   // aggregate/join/udf/univariate/exec_sql 等输出形状不定
        }
    }

    private static List<String> appendCol(List<String> up, String c) {
        List<String> out = new ArrayList<>(up);
        if (!out.contains(c)) out.add(c);
        return out;
    }

    /** 算子作用列，空则按翻译约定回落到 data。 */
    private static String colName(Map<String, Object> cfg) {
        String c = str(cfg.get("col"));
        return c.isEmpty() ? "data" : c;
    }

    private String buildOperatorView(String name, String kind, Map<String, Object> cfg, String upstream, List<String> upstreams) {
        if (upstream == null) upstream = "flink_unknown";
        String k = kind == null ? "" : kind.toLowerCase();
        switch (k) {
            case "filter":
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE (" + str(cfg.get("expression")) + ")";
            case "aggregate": {
                String groupKey = str(cfg.getOrDefault("groupKey", str(cfg.getOrDefault("groupKeys", ""))));
                String agg = str(cfg.getOrDefault("agg", str(cfg.getOrDefault("aggExpr", "COUNT(*)"))));
                if (groupKey.isEmpty()) groupKey = "*";
                if (agg.isEmpty()) agg = "COUNT(*)";
                return "CREATE VIEW " + name + " AS SELECT " + groupKey + ", " + agg + " FROM " + upstream
                        + ("*".equals(groupKey) ? "" : " GROUP BY " + groupKey);
            }
            case "select": {
                List<String> fs = fieldList(cfg.get("fields"));
                String cols = fs.isEmpty() ? "*" : String.join(", ", fs);
                return "CREATE VIEW " + name + " AS SELECT " + cols + " FROM " + upstream;
            }
            case "sort": {
                String orderBy = str(cfg.get("orderBy"));
                int limit = intOr(cfg.get("limit"), 0);
                String sql = "CREATE VIEW " + name + " AS SELECT * FROM " + upstream;
                if (!orderBy.isEmpty()) sql += " ORDER BY " + orderBy;
                if (limit > 0) sql += " LIMIT " + limit;
                return sql;
            }
            case "value_map": {
                String col = str(cfg.get("col"));
                String caseExpr = buildCase(col, str(cfg.get("mapping")));
                String alias = col.isEmpty() ? "mapped" : col + "_mapped";
                return "CREATE VIEW " + name + " AS SELECT *, " + caseExpr + " AS " + alias + " FROM " + upstream;
            }
            case "calc": {
                String exprs = str(cfg.get("exprs"));
                if (exprs.isEmpty()) exprs = str(cfg.get("expression"));
                return "CREATE VIEW " + name + " AS SELECT *" + (exprs.isEmpty() ? "" : ", " + exprs) + " FROM " + upstream;
            }
            case "dedup": {
                List<String> fs = fieldList(cfg.get("fields"));
                String cols = fs.isEmpty() ? "*" : String.join(", ", fs);
                return "CREATE VIEW " + name + " AS SELECT DISTINCT " + cols + " FROM " + upstream;
            }
            case "udf": {
                String udf = str(cfg.getOrDefault("udf", "identity"));
                String col = str(cfg.getOrDefault("col", "*"));
                return "CREATE VIEW " + name + " AS SELECT " + udf + "(" + col + ") FROM " + upstream;
            }
            // —— 记录集连接（双上游）——
            case "join": {
                String left = !upstreams.isEmpty() ? upstreams.get(0) : upstream;
                String right = upstreams.size() > 1 ? upstreams.get(1) : left;
                String jt = str(cfg.getOrDefault("joinType", "INNER")).toUpperCase();
                if (jt.isEmpty()) jt = "INNER";
                String on = str(cfg.get("onExpr"));
                if (on.isEmpty()) on = "1=1";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + left + " " + jt + " JOIN " + right + " ON " + on;
            }
            // —— 字符串处理 ——
            case "string_replace": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT *, REPLACE(" + col + ", " + quote(str(cfg.get("from")))
                        + ", " + quote(str(cfg.get("to"))) + ") AS " + col + "_repl FROM " + upstream;
            }
            case "string_ops": {
                String col = str(cfg.get("col")); String rule = str(cfg.get("expression"));
                if (rule.isEmpty()) rule = col.isEmpty() ? "data" : col;
                return "CREATE VIEW " + name + " AS SELECT *, " + rule + " AS " + (col.isEmpty() ? "data" : col) + "_ops FROM " + upstream;
            }
            case "split_field": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                String delim = str(cfg.getOrDefault("delimiter", ","));
                return "CREATE VIEW " + name + " AS SELECT *, SPLIT_INDEX(" + col + ", " + quote(delim)
                        + ", 0) AS " + col + "_split FROM " + upstream;
            }
            case "string_to_date": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                String fmt = str(cfg.getOrDefault("format", "yyyy-MM-dd"));
                return "CREATE VIEW " + name + " AS SELECT *, TO_TIMESTAMP(" + col + ", " + quote(fmt)
                        + ") AS " + col + "_dt FROM " + upstream;
            }
            case "exec_sql": {
                String s = str(cfg.get("expression"));
                String up = s.toUpperCase();
                String body = (up.contains(" FROM ") || up.startsWith("SELECT"))
                        ? s : "SELECT " + (s.isEmpty() ? "*" : s) + " FROM " + upstream;
                return "CREATE VIEW " + name + " AS " + body;
            }
            // —— 数据清洗（校验类：WHERE 过滤）——
            case "num_range": {
                String col = str(cfg.get("col")); String rule = str(cfg.get("expression"));
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE ("
                        + (rule.isEmpty() ? (col.isEmpty() ? "data" : col) + " IS NOT NULL" : rule) + ")";
            }
            case "null_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                String def = str(cfg.get("defaultVal"));
                return def.isEmpty()
                        ? "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE " + col + " IS NOT NULL"
                        : "CREATE VIEW " + name + " AS SELECT *, COALESCE(" + col + ", " + quote(def) + ") AS " + col + "_filled FROM " + upstream;
            }
            case "dup_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE " + col
                        + " IN (SELECT " + col + " FROM " + upstream + " GROUP BY " + col + " HAVING COUNT(*) > 1)";
            }
            // —— 正则校验：Flink 无 RLIKE 运算符/函数，也不是 REGEXP_LIKE，只有 regexp(str, pattern) 函数式 ——
            case "url_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream
                        + " WHERE regexp(" + col + ", " + quote("https?://[^\\s]+") + ")";
            }
            case "id_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream
                        + " WHERE regexp(" + col + ", " + quote("^[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$") + ")";
            }
            case "regex_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                String pat = str(cfg.get("pattern")); if (pat.isEmpty()) pat = ".*";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream
                        + " WHERE regexp(" + col + ", " + quote(pat) + ")";
            }
            case "data_validate": {
                String rule = str(cfg.get("expression"));
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE (" + (rule.isEmpty() ? "1=1" : rule) + ")";
            }
            // —— 脱敏处理 ——
            case "mask_partial": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                int head = Math.max(intOr(cfg.get("keepHead"), 0), 0), tail = Math.max(intOr(cfg.get("keepTail"), 0), 0);
                // 两处 FlinkSQL 约束：① 无 LENGTH()，仅 CHAR_LENGTH()（LENGTH 不在函数目录，报 No match found
                // for function signature）；② keepTail=0 时原式 (tail-1) 会拼出 '--'，被解析成行注释把语句截断。
                // 故 tail<=0 直接拼空串（尾部整段抹掉），tail>=1 时 (tail-1)>=0 不会产生 '--'。
                String tailPart = tail <= 0 ? "''"
                        : "SUBSTRING(" + col + ",GREATEST(CHAR_LENGTH(" + col + ")-" + (tail - 1) + ",1)," + tail + ")";
                String expr = "CONCAT(SUBSTRING(" + col + ",1," + head + "),'****'," + tailPart + ")";
                return "CREATE VIEW " + name + " AS SELECT *, " + expr + " AS " + col + "_masked FROM " + upstream;
            }
            case "mask_delete": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT *, NULL AS " + col + "_masked FROM " + upstream;
            }
            // —— 统计 / 采样 / 流程 ——
            case "univariate": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT COUNT(*) AS cnt, AVG(" + col + ") AS avg_val, MIN("
                        + col + ") AS min_val, MAX(" + col + ") AS max_val, STDDEV_POP(" + col + ") AS stddev_val FROM " + upstream;
            }
            case "sampling": {
                int size = intOr(cfg.get("size"), 100);
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " ORDER BY RAND() LIMIT " + size;
            }
            case "switch_case": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                String rule = str(cfg.get("expression"));
                String branch = rule.isEmpty() ? col : rule;
                return "CREATE VIEW " + name + " AS SELECT *, CASE WHEN " + col + " IS NOT NULL THEN " + branch + " END AS branch FROM " + upstream;
            }
            default:
                // js_code/java_code（任意代码）、rest_client（HTTP）、encrypt（AES/DES）、mask_random（随机）、stream_lookup（维表）：
                // 纯 FlinkSQL 无法表达，保留"规划中"
                throw new RuntimeException("算子[" + kind + "]尚未实现执行翻译（纯 FlinkSQL 无法表达：JS/Java代码、REST、加解密、随机、流查询）");
        }
    }

    /** 由 mapping JSON（{"A":"X"}）生成 CASE WHEN ... THEN ... ELSE col END 表达式。 */
    @SuppressWarnings("unchecked")
    private String buildCase(String col, String mappingJson) {
        StringBuilder sb = new StringBuilder("CASE");
        String elseExpr = col.isEmpty() ? "NULL" : col;
        if (!mappingJson.isEmpty()) {
            try {
                Map<String, Object> m = json.readValue(mappingJson, Map.class);
                for (Map.Entry<String, Object> en : m.entrySet()) {
                    sb.append(" WHEN ").append(col.isEmpty() ? "1" : quote(col)).append("=").append(quote(en.getKey()))
                            .append(" THEN ").append(quote(String.valueOf(en.getValue())));
                }
            } catch (Exception ignored) { /* mapping 非法则退化为 ELSE */ }
        }
        sb.append(" ELSE ").append(elseExpr).append(" END");
        return sb.toString();
    }

    private static String quote(String s) { return s == null || s.isEmpty() ? "''" : "'" + s.replace("'", "''") + "'"; }

    @SuppressWarnings("unchecked")
    private static List<String> fieldList(Object o) {
        if (o instanceof List) return (List<String>) o;
        if (o instanceof String s && !s.isBlank()) return List.of(s.split("\\s*,\\s*"));
        return Collections.emptyList();
    }
}
