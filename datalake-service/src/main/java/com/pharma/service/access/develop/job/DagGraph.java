package com.pharma.service.access.develop.job;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 通用工具：拓扑排序（Kahn）+ 上游查找。
 * <p>供 FlinkDagExecutor / KettleSqlExecutor 复用，避免各自的私有副本分叉。
 * <p>节点结构：{@code {id, type, data:{kind, config}}}；边结构：{@code {id, source, target}}。
 */
public final class DagGraph {

    private DagGraph() {}

    /** Kahn 拓扑排序，返回按依赖顺序排列的节点列表（入度 0 在前）。 */
    public static List<Map<String, Object>> topoSort(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
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
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> n : nodes) byId.put(str(n.get("id")), n);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : order) { Map<String, Object> n = byId.get(id); if (n != null) result.add(n); }
        return result;
    }

    /** 找 nodeId 的直接上游节点 id（取第一条入边）；alias 若非空则返回其映射名，否则返回上游 id。 */
    public static String findUpstream(List<Map<String, Object>> edges, String nodeId, Map<String, String> alias) {
        for (Map<String, Object> e : edges) {
            if (nodeId.equals(str(e.get("target")))) {
                String srcId = str(e.get("source"));
                return alias == null ? srcId : alias.get(srcId);
            }
        }
        return null;
    }

    /** 找 nodeId 的所有直接上游映射名（按入边顺序）；供 join 等多输入算子使用。 */
    public static List<String> findUpstreams(List<Map<String, Object>> edges, String nodeId, Map<String, String> alias) {
        List<String> ups = new ArrayList<>();
        for (Map<String, Object> e : edges) {
            if (nodeId.equals(str(e.get("target")))) {
                String srcId = str(e.get("source"));
                ups.add(alias == null ? srcId : alias.get(srcId));
            }
        }
        return ups;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
