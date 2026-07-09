package com.pharma.service.access.develop.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flink 图形化作业（job_type=flink_dag）：把 DAG（source/operator/sink）翻译成 FlinkSQL，交 FlinkSqlExecutor 执行。
 * <p>拓扑排序后：source/sink 生成 CREATE TABLE...WITH(connector)；operator 生成 CREATE VIEW
 * （filter=WHERE / aggregate=GROUP BY / join / udf）；sink 末尾生成 INSERT INTO...SELECT。
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

        List<Map<String, Object>> order = topoSort(nodes, edges);
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
                String upstream = findUpstream(edges, id, alias);
                sql.append(buildOperatorView(name, kind, cfg, upstream)).append(";\n");
            }
        }
        // sink 的 INSERT INTO ... SELECT * FROM 上游
        for (Map<String, Object> node : nodes) {
            if (!"sink".equals(str(node.get("type")))) continue;
            String id = str(node.get("id"));
            String upstream = findUpstream(edges, id, alias);
            if (upstream == null) continue;
            sql.append("INSERT INTO ").append(alias.get(id)).append(" SELECT * FROM ").append(upstream).append(";\n");
        }
        log.append("DAG 翻译为 FlinkSQL:\n").append(sql).append("\n");

        Map<String, Object> translated = new LinkedHashMap<>(task);
        translated.put("sql_content", sql.toString());
        return sqlExecutor.execute(taskId, translated, log);
    }

    private String buildCreateTable(String name, String kind, Map<String, Object> cfg) {
        String connector = "kafka".equalsIgnoreCase(kind) ? "kafka" : "jdbc";
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
        if ("filter".equals(k)) {
            return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream + " WHERE (" + str(cfg.get("expression")) + ")";
        } else if ("aggregate".equals(k)) {
            String groupKey = str(cfg.getOrDefault("groupKey", "*"));
            String agg = str(cfg.getOrDefault("agg", "COUNT(*)"));
            return "CREATE VIEW " + name + " AS SELECT " + groupKey + ", " + agg + " FROM " + upstream + " GROUP BY " + groupKey;
        } else if ("udf".equals(k)) {
            String udf = str(cfg.getOrDefault("udf", "identity"));
            String col = str(cfg.getOrDefault("col", "*"));
            return "CREATE VIEW " + name + " AS SELECT " + udf + "(" + col + ") FROM " + upstream;
        }
        // join 或未知：直通（完整双上游 JOIN 需扩展）
        return "CREATE VIEW " + name + " AS SELECT * FROM " + upstream;
    }

    private String findUpstream(List<Map<String, Object>> edges, String nodeId, Map<String, String> alias) {
        for (Map<String, Object> e : edges) {
            if (nodeId.equals(str(e.get("target")))) return alias.get(str(e.get("source")));
        }
        return null;
    }

    /** Kahn 拓扑排序 */
    private List<Map<String, Object>> topoSort(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Integer> inDeg = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (Map<String, Object> n : nodes) inDeg.putIfAbsent(str(n.get("id")), 0);
        for (Map<String, Object> e : edges) {
            String s = str(e.get("source")), t = str(e.get("target"));
            adj.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
            inDeg.merge(t, 1, (a, b) -> a + b);
        }
        Deque<String> q = new ArrayDeque<>();
        for (Map.Entry<String, Integer> en : inDeg.entrySet()) if (en.getValue() == 0) q.add(en.getKey());
        List<String> order = new ArrayList<>();
        while (!q.isEmpty()) {
            String cur = q.poll();
            order.add(cur);
            for (String nb : adj.getOrDefault(cur, Collections.emptyList())) {
                if (inDeg.merge(nb, -1, (a, b) -> a + b) == 0) q.add(nb);
            }
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> n : nodes) byId.put(str(n.get("id")), n);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : order) { Map<String, Object> n = byId.get(id); if (n != null) result.add(n); }
        return result;
    }
}
