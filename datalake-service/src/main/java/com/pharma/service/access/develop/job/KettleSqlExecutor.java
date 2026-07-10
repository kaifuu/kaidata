package com.pharma.service.access.develop.job;

import com.pharma.service.access.develop.DevScriptExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kettle/Hop 图形化作业（job_type=kettle_hop）：把 DAG 翻译成标准 SQL，在任务绑定的数据源（通常是 StarRocks）上执行。
 * <p>模型与 FlinkDagExecutor 一致：source(输入)/operator(转换)/sink(输出)，data.kind 区分具体节点。
 * <p>翻译方式：每个 operator 把上游包成嵌套子查询（{@code SELECT ... FROM (<上游SQL>) q_<id> ...}），
 * 通用可靠、无 CTE+INSERT 语法差异风险；最终 {@code INSERT INTO <sink> SELECT ... FROM (<上游SQL>)}。
 * <p>table 类输入/输出完整可执行；csv/excel/json/kafka 类翻译为暂存表引用并在日志标注需预先载入。
 * <p>取代旧的 KettleHopExecutor（调 Hop Server REST，dag_json 未翻译）。
 */
@Component
public class KettleSqlExecutor extends AbstractHttpExecutor {

    @Autowired private DevScriptExecutor scriptExecutor;

    @Override public String jobType() { return "kettle_sql_fallback"; }  // 已让位给 KettleHopExecutor（docker exec hop-run.sh CLI 执行 Hop 引擎），保留作 SQL 翻译回退

    @Override
    public Map<String, Object> execute(long taskId, Map<String, Object> task, StringBuilder log) throws Exception {
        long dsId = lng(task.get("datasource_id"));
        if (dsId <= 0) throw new RuntimeException("Kettle 作业未绑定执行数据源（datasource_id）");

        Map<String, Object> dag = parseJson(str(task.get("dag_json")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dag.getOrDefault("nodes", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) dag.getOrDefault("edges", Collections.emptyList());
        if (nodes.isEmpty()) throw new RuntimeException("DAG 没有节点");

        // id → node 索引
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) byId.put(str(n.get("id")), n);

        // 校验拓扑可排序（顺带检测环）
        DagGraph.topoSort(nodes, edges);

        List<Map<String, Object>> sinks = new ArrayList<>();
        for (Map<String, Object> n : nodes) if ("sink".equals(str(n.get("type")))) sinks.add(n);

        long rowsRead = 0;
        int stmts = 0;
        if (sinks.isEmpty()) {
            // 无输出节点：把末个算子的结果当作预览 SELECT 执行
            String sql = bodyOf(lastOperator(nodes, edges), edges, byId, log, new HashMap<>());
            log.append("（无 sink，按预览 SELECT 执行）\nSQL:\n").append(sql).append("\n");
            rowsRead = runAndCount(dsId, sql, log);
            stmts = 1;
        } else {
            for (Map<String, Object> sink : sinks) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = (Map<String, Object>) ((Map<String, Object>) sink.getOrDefault("data", Map.of())).getOrDefault("config", Map.of());
                String kind = str(((Map<String, Object>) sink.getOrDefault("data", Map.of())).get("kind"));
                String table = resolveTable(cfg, kind);
                if (!"table_output".equals(kind) && !"table".equals(kind)) {
                    log.append("跳过非表输出节点 ").append(str(sink.get("id"))).append("（").append(kind)
                            .append("，需外部处理）\n");
                    continue;
                }
                String upstreamId = DagGraph.findUpstream(edges, str(sink.get("id")), null);
                Map<String, Object> up = byId.get(upstreamId);
                String upstreamBody = up == null ? "SELECT 1" : bodyOf(up, edges, byId, log, new HashMap<>());
                String sql = "INSERT INTO " + table + " SELECT * FROM (" + upstreamBody + ") q_" + sanitize(str(sink.get("id")));
                log.append("执行 sink[").append(str(sink.get("id"))).append("]:\n").append(sql).append("\n");
                rowsRead += runAndCount(dsId, sql, log);
                stmts++;
            }
            if (stmts == 0) throw new RuntimeException("DAG 没有可执行的表输出节点");
        }
        log.append("Kettle DAG 执行完成，共 ").append(stmts).append(" 条语句，影响行 rowsRead=").append(rowsRead).append("\n");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineJobId", "");
        out.put("rowsRead", rowsRead);
        return out;
    }

    /** 在数据源上执行 SQL，返回影响行数（INSERT=updateCount，SELECT=结果行数）。 */
    private long runAndCount(long dsId, String sql, StringBuilder log) {
        Map<String, Object> r = scriptExecutor.executeSql(dsId, sql);
        if (!"SUCCESS".equals(str(r.get("status")))) {
            throw new RuntimeException("SQL 执行失败: " + r.get("msg"));
        }
        long n = lng(r.get("rowsRead"));
        log.append("  → OK rowsRead=").append(n).append("\n");
        return n;
    }

    /**
     * 递归生成节点的 SELECT body（不含外层括号、不含结尾分号）。
     * memo 缓存 nodeId→body，避免菱形依赖重复展开。
     */
    private String bodyOf(Map<String, Object> node, List<Map<String, Object>> edges,
                          Map<String, Map<String, Object>> byId, StringBuilder log, Map<String, String> memo) {
        if (node == null) return "SELECT 1";
        String id = str(node.get("id"));
        String cached = memo.get(id);
        if (cached != null) return cached;

        String type = str(node.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) node.getOrDefault("data", Map.of());
        String kind = str(data.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) data.getOrDefault("config", Map.of());
        String alias = "q_" + sanitize(id);
        String body;

        if ("source".equals(type)) {
            String table = resolveTable(cfg, kind);
            if (!"table_input".equals(kind) && !"table".equals(kind)) {
                log.append("注意: 输入节点 ").append(id).append("（").append(kind)
                        .append("）需预先将数据载入表 ").append(table).append(" 方可执行\n");
            }
            String cols = fieldList(cfg.get("fields"));
            body = "SELECT " + (cols.isEmpty() ? "*" : cols) + " FROM " + table;
        } else if ("operator".equals(type)) {
            String upId = DagGraph.findUpstream(edges, id, null);
            String upBody = bodyOf(byId.get(upId), edges, byId, log, memo);
            String from = " FROM (" + upBody + ") " + alias;
            body = operatorSelect(kind, cfg, from);
        } else {
            // sink 当作上游使用时退化为 SELECT
            String upId = DagGraph.findUpstream(edges, id, null);
            body = "SELECT * FROM (" + bodyOf(byId.get(upId), edges, byId, log, memo) + ") " + alias;
        }
        memo.put(id, body);
        return body;
    }

    private String operatorSelect(String kind, Map<String, Object> cfg, String from) {
        String k = kind == null ? "" : kind.toLowerCase();
        switch (k) {
            case "filter":
                return "SELECT *" + from + " WHERE (" + str(cfg.get("expression")) + ")";
            case "aggregate": {
                String groupKey = str(cfg.getOrDefault("groupKeys", str(cfg.get("groupKey"))));
                String agg = str(cfg.getOrDefault("aggExpr", str(cfg.get("agg"))));
                if (groupKey.isEmpty()) groupKey = "1";
                if (agg.isEmpty()) agg = "COUNT(*)";
                return "SELECT " + groupKey + ", " + agg + from + " GROUP BY " + groupKey;
            }
            case "select": {
                String fs = fieldList(cfg.get("fields"));
                return "SELECT " + (fs.isEmpty() ? "*" : fs) + from;
            }
            case "sort": {
                String orderBy = str(cfg.get("orderBy"));
                int limit = intOr(cfg.get("limit"), 0);
                String sql = "SELECT *" + from;
                if (!orderBy.isEmpty()) sql += " ORDER BY " + orderBy;
                if (limit > 0) sql += " LIMIT " + limit;
                return sql;
            }
            case "value_map": {
                String col = str(cfg.get("col"));
                String caseExpr = buildCase(col, str(cfg.get("mapping")));
                String out = col.isEmpty() ? "mapped" : col + "_mapped";
                return "SELECT *" + (caseExpr.isEmpty() ? "" : ", " + caseExpr + " AS " + out) + from;
            }
            case "calc": {
                String exprs = str(cfg.getOrDefault("exprs", str(cfg.get("expression"))));
                return "SELECT *" + (exprs.isEmpty() ? "" : ", " + exprs) + from;
            }
            case "dedup": {
                String fs = fieldList(cfg.get("fields"));
                return "SELECT DISTINCT " + (fs.isEmpty() ? "*" : fs) + from;
            }
            default:
                throw new RuntimeException("算子[" + kind + "]尚未实现执行翻译");
        }
    }

    /** 解析节点配置得到表名；非 table 类以 kind 作为暂存表后缀（提示需预先载入）。 */
    private String resolveTable(Map<String, Object> cfg, String kind) {
        String t = str(cfg.get("tableName"));
        if (!t.isEmpty()) return t;
        if (!"table_input".equals(kind) && !"table_output".equals(kind) && !"table".equals(kind)) {
            return "staging_" + (kind == null ? "src" : kind);
        }
        return "unknown_table";
    }

    /** 由 mapping JSON（{"A":"X"}）生成 CASE WHEN ... THEN ... ELSE col END，无法解析返回空串。 */
    private String buildCase(String col, String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) return "";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = json.readValue(mappingJson, Map.class);
            StringBuilder sb = new StringBuilder("CASE");
            for (Map.Entry<String, Object> en : m.entrySet()) {
                sb.append(" WHEN ").append(col.isEmpty() ? "1" : quote(col)).append("=").append(quote(en.getKey()))
                        .append(" THEN ").append(quote(String.valueOf(en.getValue())));
            }
            sb.append(" ELSE ").append(col.isEmpty() ? "NULL" : quote(col)).append(" END");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 取拓扑末端的 operator/source（无 sink 时用作预览目标）。 */
    private Map<String, Object> lastOperator(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        List<Map<String, Object>> order = DagGraph.topoSort(nodes, edges);
        for (int i = order.size() - 1; i >= 0; i--) {
            Map<String, Object> n = order.get(i);
            if (!"sink".equals(str(n.get("type")))) return n;
        }
        return nodes.get(0);
    }

    private static String sanitize(String id) { return id == null ? "x" : id.replaceAll("[^a-zA-Z0-9]", "_"); }
    private static String quote(String s) { return s == null || s.isEmpty() ? "''" : "'" + s.replace("'", "''") + "'"; }

    @SuppressWarnings("unchecked")
    private static String fieldList(Object o) {
        if (o instanceof List && !((List<?>) o).isEmpty()) return String.join(", ", (List<String>) o);
        if (o instanceof String s && !s.isBlank()) return s;
        return "";
    }

    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
