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
                sql.append(buildCreateTable(name, kind, cfg)).append(";\n");
            } else if ("operator".equals(type)) {
                String upstream = DagGraph.findUpstream(edges, id, alias);
                sql.append(buildOperatorView(name, kind, cfg, upstream)).append(";\n");
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

    private String buildOperatorView(String name, String kind, Map<String, Object> cfg, String upstream) {
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
            default:
                // join 或未知算子：未实现翻译（规划中算子运行时报错，避免静默直通误导）
                throw new RuntimeException("算子[" + kind + "]尚未实现执行翻译");
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
