package com.pharma.service.controller;

import com.pharma.service.access.meta.LineageExtractor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 统一血缘查询 [SYS_ADMIN]：从 meta.gov_meta_lineage_edge 做 N 跳图谱 / 字段级 / 影响分析 / 覆盖统计 / 全量重建。
 * 边由 {@link LineageExtractor} 在作业保存时解析写入。驱动前端「血缘分析」全屏图谱页。
 */
@RestController
@RequestMapping("/api/data-gov/lineage")
@CrossOrigin(origins = "*")
public class DataLineageController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LineageExtractor extractor;

    /** N 跳血缘图谱。direction: up(上游)/down(下游)/both。返回 {nodes, links}，node id = 表名(小写)。 */
    @GetMapping("/graph")
    public Map<String, Object> graph(@RequestParam String table,
                                     @RequestParam(required = false) String schema,
                                     @RequestParam(required = false) Long dsId,
                                     @RequestParam(defaultValue = "both") String direction,
                                     @RequestParam(defaultValue = "2") int depth) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> edges = safeList(
                "SELECT src_ds_id,src_schema,src_table,tgt_ds_id,tgt_schema,tgt_table,edge_type,job_name FROM meta.gov_meta_lineage_edge");
        Map<String, List<Map<String, Object>>> bySrc = new HashMap<>(), byTgt = new HashMap<>();
        for (Map<String, Object> e : edges) {
            bySrc.computeIfAbsent(low(str(e.get("src_table"))), k -> new ArrayList<>()).add(e);
            byTgt.computeIfAbsent(low(str(e.get("tgt_table"))), k -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> linkSeen = new HashSet<>();
        String center = low(table);
        addNode(nodes, center, "table", dsId == null ? 0 : dsId);
        visited.add(center);
        Deque<String> q = new ArrayDeque<>();
        Deque<Integer> ql = new ArrayDeque<>();
        q.add(center);
        ql.add(0);
        boolean up = direction.contains("up") || direction.equals("both");
        boolean down = direction.contains("down") || direction.equals("both");
        while (!q.isEmpty()) {
            String cur = q.poll();
            int lvl = ql.poll();
            if (lvl >= depth) continue;
            if (up) {
                for (Map<String, Object> e : byTgt.getOrDefault(cur, List.of())) {
                    String nb = low(str(e.get("src_table")));
                    addLink(links, linkSeen, nb, cur, str(e.get("edge_type")), str(e.get("job_name")));
                    if (visited.add(nb)) {
                        addNode(nodes, nb, "table", lng(e.get("src_ds_id")));
                        q.add(nb);
                        ql.add(lvl + 1);
                    }
                }
            }
            if (down) {
                for (Map<String, Object> e : bySrc.getOrDefault(cur, List.of())) {
                    String nb = low(str(e.get("tgt_table")));
                    String cat = "EXTERNAL".equalsIgnoreCase(str(e.get("tgt_schema"))) ? "external" : "table";
                    addLink(links, linkSeen, cur, nb, str(e.get("edge_type")), str(e.get("job_name")));
                    if (visited.add(nb)) {
                        addNode(nodes, nb, cat, lng(e.get("tgt_ds_id")));
                        q.add(nb);
                        ql.add(lvl + 1);
                    }
                }
            }
        }
        return Map.of("nodes", nodes, "links", links);
    }

    /** 影响分析：下游 N 跳（供资产下线评估）。 */
    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam String table,
                                      @RequestParam(required = false) String schema,
                                      @RequestParam(required = false) Long dsId,
                                      @RequestParam(defaultValue = "3") int depth) {
        Authz.require(Authz.SYS_ADMIN);
        return graph(table, schema, dsId, "down", depth);
    }

    /** 字段级血缘：读 gov_meta_field_map 中该字段的来源映射（解析器暂以表级为主，字段级靠人工 mapping）。 */
    @GetMapping("/field")
    public Map<String, Object> field(@RequestParam long metaId, @RequestParam String field) {
        Authz.require(Authz.SYS_ADMIN);
        return Map.of("mappings", safeList(
                "SELECT logical_field,src_ds_id,src_schema,src_table,src_field,job_type FROM meta.gov_meta_field_map WHERE meta_id=? AND logical_field=?",
                metaId, field));
    }

    /** 覆盖统计：总表数 / 有血缘表数 / 覆盖率 / 按 edge_type 分布。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Authz.require(Authz.SYS_ADMIN);
        long total = cnt("SELECT COUNT(*) FROM meta.gov_meta_table");
        long edgeCount = cnt("SELECT COUNT(*) FROM meta.gov_meta_lineage_edge");
        long withEdge = cnt(
                "SELECT COUNT(*) FROM (SELECT src_table t FROM meta.gov_meta_lineage_edge WHERE src_table<>'' " +
                        "UNION SELECT tgt_table FROM meta.gov_meta_lineage_edge WHERE tgt_schema<>'EXTERNAL' AND tgt_table<>'') x");
        Map<String, Object> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : safeList("SELECT edge_type, COUNT(*) AS c FROM meta.gov_meta_lineage_edge GROUP BY edge_type")) {
            byType.put(str(r.get("edge_type")), lng(r.get("c")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalTables", total);
        out.put("edgeCount", edgeCount);
        out.put("withLineage", withEdge);
        out.put("coverage", total == 0 ? 0 : Math.round(withEdge * 100.0 / total));
        out.put("byType", byType);
        return out;
    }

    /** 全量重建血缘边表（管理员）。 */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild() {
        Authz.require(Authz.SYS_ADMIN);
        return extractor.rebuildAll();
    }

    // ---- 助手 ----

    private void addNode(List<Map<String, Object>> nodes, String label, String category, long dsId) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", label);
        n.put("label", label);
        n.put("category", category);
        n.put("ds_id", dsId);
        nodes.add(n);
    }

    private void addLink(List<Map<String, Object>> links, Set<String> seen, String src, String tgt, String edgeType, String jobName) {
        String key = src + ">" + tgt + ":" + edgeType;
        if (!seen.add(key)) return;
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("source", src);
        l.put("target", tgt);
        l.put("edgeType", edgeType);
        l.put("jobName", jobName);
        links.add(l);
    }

    private static String low(String s) { return s == null ? "" : s.toLowerCase(); }

    private List<Map<String, Object>> safeList(String sql, Object... args) {
        try { return args.length == 0 ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args); }
        catch (Exception e) { return List.of(); }
    }

    private long cnt(String sql, Object... args) {
        try { return args.length == 0 ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, args); }
        catch (Exception e) { return 0; }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
