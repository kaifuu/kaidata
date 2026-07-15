package com.pharma.service.access.develop.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink 图形化作业（job_type=flink_dag）：把 DAG（source/operator/sink）翻译成 FlinkSQL，交 FlinkSqlExecutor 执行。
 * <p>拓扑排序后：source/sink 生成 CREATE TABLE...WITH(connector)；operator 生成 CREATE VIEW
 * （filter=WHERE / aggregate=GROUP BY / select / sort=ORDER BY / value_map=CASE / calc=算术列 / dedup=DISTINCT / udf / join）；
 * sink 末尾生成 INSERT INTO...SELECT。
 */
@Component
public class FlinkDagExecutor extends AbstractHttpExecutor {

    @Autowired private FlinkSqlExecutor sqlExecutor;

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
                sql.append(buildCreateTable(name, kind, cfg)).append(";\n");
            } else if ("operator".equals(type)) {
                String upstream = DagGraph.findUpstream(edges, id, alias);
                List<String> upstreams = DagGraph.findUpstreams(edges, id, alias);
                sql.append(buildOperatorView(name, kind, cfg, upstream, upstreams)).append(";\n");
            }
        }
        // sink 的 INSERT INTO ... SELECT * FROM 上游
        for (Map<String, Object> node : nodes) {
            if (!"sink".equals(str(node.get("type")))) continue;
            String id = str(node.get("id"));
            String upstream = DagGraph.findUpstream(edges, id, alias);
            if (upstream == null) continue;
            sql.append("INSERT INTO ").append(alias.get(id)).append(" SELECT * FROM ").append(upstream).append(";\n");
        }
        log.append("DAG 翻译为 FlinkSQL:\n").append(sql).append("\n");

        Map<String, Object> translated = new LinkedHashMap<>(task);
        translated.put("sql_content", sql.toString());
        return sqlExecutor.execute(taskId, translated, log);
    }

    /** Flink 真正支持的 source/sink kind：table（jdbc）+ kafka 系列。其余（csv/excel/json/xml/text/rest/generate/insert_update）无连接器。 */
    private static boolean isFlinkSupportedEndpoint(String kind) {
        if (kind == null) return false;
        String k = kind.toLowerCase();
        return "table".equals(k) || "kafka".equals(k) || "kafka_input".equals(k) || "kafka_output".equals(k);
    }

    private String buildCreateTable(String name, String kind, Map<String, Object> cfg) {
        String connector = "kafka".equalsIgnoreCase(kind) || "kafka_input".equalsIgnoreCase(kind) || "kafka_output".equalsIgnoreCase(kind) ? "kafka" : "jdbc";
        String tableName = str(cfg.get("tableName"));
        String topic = str(cfg.get("topic"));
        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(name).append(" (");
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) cfg.getOrDefault("fields", Collections.emptyList());
        if (fields.isEmpty()) sb.append("data STRING");
        else sb.append(String.join(" STRING, ", fields)).append(" STRING");
        sb.append(") WITH ('connector'='").append(connector).append("'");
        if ("jdbc".equals(connector) && !tableName.isEmpty()) {
            sb.append(", 'url'='jdbc:mysql://host:9030/db', 'table-name'='").append(tableName).append("'");
        } else if ("kafka".equals(connector) && !topic.isEmpty()) {
            sb.append(", 'topic'='").append(topic).append("', 'properties.bootstrap.servers'='pharma-kafka:9092', 'format'='json'");
        }
        sb.append(")");
        return sb.toString();
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
            case "url_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE " + col
                        + " RLIKE " + quote("https?://[^\\s]+");
            }
            case "id_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE " + col
                        + " RLIKE " + quote("^[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$");
            }
            case "regex_check": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                String pat = str(cfg.get("pattern")); if (pat.isEmpty()) pat = ".*";
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE " + col + " RLIKE " + quote(pat);
            }
            case "data_validate": {
                String rule = str(cfg.get("expression"));
                return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE (" + (rule.isEmpty() ? "1=1" : rule) + ")";
            }
            // —— 脱敏处理 ——
            case "mask_partial": {
                String col = str(cfg.get("col")); if (col.isEmpty()) col = "data";
                int head = intOr(cfg.get("keepHead"), 0), tail = intOr(cfg.get("keepTail"), 0);
                String expr = "CONCAT(SUBSTRING(" + col + ",1," + head + "),'****',SUBSTRING(" + col
                        + ",GREATEST(LENGTH(" + col + ")-" + (tail - 1) + ",1)," + tail + "))";
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
