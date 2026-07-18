package com.pharma.service.access.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.util.SqlBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.*;

/**
 * 统一血缘解析器：把各作业（离线接入 / 实时 / 接出 / 离线开发 JDBC·Flink SQL·DAG·Kettle / 脚本 / 工作流）
 * 的「源表 → 目标表」解析为边，写入 {@code meta.gov_meta_lineage_edge}，供 DataLineageController 图谱查询。
 *
 * <p>表级血缘（src_field / tgt_field 留空）。SQL 解析复用 {@link TableExtractor}（FROM/JOIN 抽源表、INSERT 抽目标表）；
 * DAG 解析复用 Kettle/Flink 的节点模型（source table_input → sink table_output，取 {@code data.config.tableName}），
 * 单流 DAG 准确，多流 DAG 按 source×sink 笛卡尔近似（血缘为提示性，可接受）。
 *
 * <p>作业保存即调 {@link #rebuild}（按类别删旧边 + 重解析）；管理端可调 {@link #rebuildAll} 全量重建。
 * 解析异常 per-作业吞掉，不阻塞整体。
 */
@Component
public class LineageExtractor {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public LineageExtractor(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ==================== 对外：全量重建 / 单作业重建 ====================

    /** 全量重建：清空边表，重新解析全部作业源。返回写入边数。 */
    public Map<String, Object> rebuildAll() {
        try { jdbc.update("DELETE FROM meta.gov_meta_lineage_edge"); } catch (Exception ignored) {}
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : safeList("SELECT id FROM meta.ing_offline_job")) collectOffline(out, lng(r.get("id")));
        for (Map<String, Object> r : safeList("SELECT id FROM meta.ing_stream_job")) collectStream(out, lng(r.get("id")));
        for (Map<String, Object> r : safeList("SELECT id FROM meta.dev_export")) collectExport(out, lng(r.get("id")));
        for (Map<String, Object> r : safeList("SELECT id FROM meta.dev_offline_task")) collectDevTask(out, lng(r.get("id")));
        for (Map<String, Object> r : safeList("SELECT id FROM meta.dev_script")) collectScript(out, lng(r.get("id")));
        for (Map<String, Object> r : safeList("SELECT id FROM meta.dev_workflow")) collectWorkflow(out, lng(r.get("id")));
        insertAll(out);
        return Map.of("edges", out.size());
    }

    /** 单作业重建：按类别删旧边 + 重解析该作业。category ∈ OFFLINE/STREAM/EXPORT/DEV_TASK/SCRIPT/WORKFLOW。 */
    public int rebuild(String category, long jobId) {
        deleteByCategory(category, jobId);
        List<Map<String, Object>> out = new ArrayList<>();
        switch (category) {
            case "OFFLINE" -> collectOffline(out, jobId);
            case "STREAM" -> collectStream(out, jobId);
            case "EXPORT" -> collectExport(out, jobId);
            case "DEV_TASK" -> collectDevTask(out, jobId);
            case "SCRIPT" -> collectScript(out, jobId);
            case "WORKFLOW" -> collectWorkflow(out, jobId);
            default -> { return 0; }
        }
        insertAll(out);
        return out.size();
    }

    // ==================== 各作业源：单作业收集（全量 = 遍历 id 调此） ====================

    private void collectOffline(List<Map<String, Object>> out, long jobId) {
        Map<String, Object> r = one("SELECT name,source_ds_id,source_table,target_ds_id,target_db,target_table FROM meta.ing_offline_job WHERE id=?", jobId);
        if (r == null) return;
        String[] src = SqlBuilder.splitTable(str(r.get("source_table")));
        addIf(out, edge(lng(r.get("source_ds_id")), src[0], src[1],
                lng(r.get("target_ds_id")), str(r.get("target_db")), str(r.get("target_table")),
                "OFFLINE", jobId, str(r.get("name"))));
    }

    private void collectStream(List<Map<String, Object>> out, long jobId) {
        Map<String, Object> r = one("SELECT name,source_ds_id,source_query,target_db,target_table FROM meta.ing_stream_job WHERE id=?", jobId);
        if (r == null) return;
        long ds = lng(r.get("source_ds_id"));
        String tSchema = str(r.get("target_db")), tTable = str(r.get("target_table"));
        for (String s : TableExtractor.parse(str(r.get("source_query")))) {
            String[] ss = SqlBuilder.splitTable(s);
            addIf(out, edge(ds, ss[0], ss[1], ds, tSchema, tTable, "STREAM", jobId, str(r.get("name"))));
        }
    }

    private void collectExport(List<Map<String, Object>> out, long jobId) {
        Map<String, Object> r = one("SELECT name,source_ds_id,source_query,target_type FROM meta.dev_export WHERE id=?", jobId);
        if (r == null) return;
        long ds = lng(r.get("source_ds_id"));
        String tt = str(r.get("target_type"));
        for (String s : TableExtractor.parse(str(r.get("source_query")))) {
            String[] ss = SqlBuilder.splitTable(s);
            addIf(out, edge(ds, ss[0], ss[1], 0, "EXTERNAL", tt, "EXPORT", jobId, str(r.get("name"))));
        }
    }

    private void collectDevTask(List<Map<String, Object>> out, long jobId) {
        Map<String, Object> t = one("SELECT name,job_type,datasource_id,sql_content,dag_json FROM meta.dev_offline_task WHERE id=?", jobId);
        if (t == null) return;
        out.addAll(parseDevTask(t, null, jobId, str(t.get("name"))));
    }

    private void collectScript(List<Map<String, Object>> out, long jobId) {
        Map<String, Object> s = one("SELECT name,script_type,datasource_id,content FROM meta.dev_script WHERE id=?", jobId);
        if (s == null) return;
        out.addAll(parseScript(s, "DEV_SCRIPT", jobId, str(s.get("name"))));
    }

    private void collectWorkflow(List<Map<String, Object>> out, long workflowId) {
        Map<String, Object> wf = one("SELECT name FROM meta.dev_workflow WHERE id=?", workflowId);
        if (wf == null) return;
        String wfName = str(wf.get("name"));
        for (Map<String, Object> n : safeList("SELECT node_type,node_id FROM meta.dev_workflow_node WHERE workflow_id=?", workflowId)) {
            String nt = str(n.get("node_type")).toLowerCase();
            long nid = lng(n.get("node_id"));
            if (nt.contains("script")) {
                Map<String, Object> s = one("SELECT name,script_type,datasource_id,content FROM meta.dev_script WHERE id=?", nid);
                if (s != null) out.addAll(parseScript(s, "DEV_WORKFLOW", workflowId, wfName + "/" + str(s.get("name"))));
            } else if (nt.contains("task")) {
                Map<String, Object> t = one("SELECT name,job_type,datasource_id,sql_content,dag_json FROM meta.dev_offline_task WHERE id=?", nid);
                if (t != null) out.addAll(parseDevTask(t, "DEV_WORKFLOW", workflowId, wfName + "/" + str(t.get("name"))));
            }
        }
    }

    // ==================== 解析内核：dev_task / script / dag ====================

    /** 解析离线开发任务。forcedEdgeType 非空则用之（工作流展开场景），否则按 job_type 推导。 */
    private List<Map<String, Object>> parseDevTask(Map<String, Object> t, String forcedEdgeType, long jobId, String jobName) {
        List<Map<String, Object>> out = new ArrayList<>();
        String jt = str(t.get("job_type")).toLowerCase();
        String edgeType = forcedEdgeType != null ? forcedEdgeType : devTaskEdgeType(jt);
        long ds = lng(t.get("datasource_id"));
        if (jt.contains("sql")) {
            // jdbc_sql / flink_sql：解析 sql_content 的 INSERT 目标 + FROM/JOIN 源
            String sql = str(t.get("sql_content"));
            String tgt = TableExtractor.parseInsertTarget(sql);
            if (tgt != null) {
                String[] ts = SqlBuilder.splitTable(tgt);
                for (String s : TableExtractor.parse(sql)) {
                    String[] ss = SqlBuilder.splitTable(s);
                    addIf(out, edge(ds, ss[0], ss[1], ds, ts[0], ts[1], edgeType, jobId, jobName));
                }
            }
        } else if (jt.contains("dag") || jt.contains("kettle")) {
            // flink_dag / kettle_hop：遍历 dag_json 节点
            out.addAll(parseDag(str(t.get("dag_json")), ds, edgeType, jobId, jobName));
        }
        // flink_jar 等无 SQL/DAG 结构，不解析
        return out;
    }

    /** 解析脚本（仅 script_type=SQL 的 INSERT...SELECT）。 */
    private List<Map<String, Object>> parseScript(Map<String, Object> s, String edgeType, long jobId, String jobName) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!"sql".equalsIgnoreCase(str(s.get("script_type")))) return out;
        long ds = lng(s.get("datasource_id"));
        String content = str(s.get("content"));
        String tgt = TableExtractor.parseInsertTarget(content);
        if (tgt == null) return out;
        String[] ts = SqlBuilder.splitTable(tgt);
        for (String src : TableExtractor.parse(content)) {
            String[] ss = SqlBuilder.splitTable(src);
            addIf(out, edge(ds, ss[0], ss[1], ds, ts[0], ts[1], edgeType, jobId, jobName));
        }
        return out;
    }

    /** 解析 DAG（Flink/Kettle 同构）：收集 source 表集合 × sink 表集合，建笛卡尔边。 */
    private List<Map<String, Object>> parseDag(String dagJson, long dsId, String edgeType, long jobId, String jobName) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (dagJson == null || dagJson.isBlank()) return out;
        try {
            JsonNode root = json.readTree(dagJson);
            JsonNode nodes = root.path("nodes");
            if (nodes.isMissingNode() && root.isArray()) nodes = root;
            if (!nodes.isArray()) return out;
            List<String[]> sources = new ArrayList<>(), sinks = new ArrayList<>();
            for (JsonNode n : nodes) {
                String type = n.path("type").asText("");
                String kind = n.path("data").path("kind").asText("");
                String tbl = n.path("data").path("config").path("tableName").asText("");
                if (tbl.isEmpty()) continue;
                String sch = n.path("data").path("config").path("schemaName").asText("");
                if ("source".equals(type) && (eq(kind, "table_input") || eq(kind, "table"))) sources.add(new String[]{sch, tbl});
                else if ("sink".equals(type) && (eq(kind, "table_output") || eq(kind, "table"))) sinks.add(new String[]{sch, tbl});
            }
            for (String[] s : sources) for (String[] t : sinks)
                addIf(out, edge(dsId, s[0], s[1], dsId, t[0], t[1], edgeType, jobId, jobName));
        } catch (Exception ignored) {}
        return out;
    }

    private static String devTaskEdgeType(String jt) {
        if (jt.contains("flink_sql") || jt.equals("flink_sql")) return "DEV_FLINK_SQL";
        if (jt.contains("flink_dag")) return "DEV_FLINK_DAG";
        if (jt.contains("kettle")) return "DEV_KETTLE_HOP";
        return "DEV_JDBC_SQL"; // jdbc_sql 兼容默认
    }

    // ==================== 写入 / 删除 ====================

    private void insertAll(List<Map<String, Object>> edges) {
        long base = System.currentTimeMillis();
        Timestamp now = new Timestamp(base);
        int i = 0;
        for (Map<String, Object> e : edges) {
            try {
                jdbc.update("INSERT INTO meta.gov_meta_lineage_edge(id,src_ds_id,src_schema,src_table,tgt_ds_id,tgt_schema,tgt_table,edge_type,job_id,job_name,create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        base + (long) (i++), lng(e.get("src_ds_id")), str(e.get("src_schema")), str(e.get("src_table")),
                        lng(e.get("tgt_ds_id")), str(e.get("tgt_schema")), str(e.get("tgt_table")),
                        str(e.get("edge_type")), lng(e.get("job_id")), str(e.get("job_name")), now);
            } catch (Exception ignored) {}
        }
    }

    private void deleteByCategory(String category, long jobId) {
        String types = switch (category) {
            case "OFFLINE" -> "'OFFLINE'";
            case "STREAM" -> "'STREAM'";
            case "EXPORT" -> "'EXPORT'";
            case "DEV_TASK" -> "'DEV_JDBC_SQL','DEV_FLINK_SQL','DEV_FLINK_DAG','DEV_KETTLE_HOP'";
            case "SCRIPT" -> "'DEV_SCRIPT'";
            case "WORKFLOW" -> "'DEV_WORKFLOW'";
            default -> "'__none__'";
        };
        try { jdbc.update("DELETE FROM meta.gov_meta_lineage_edge WHERE job_id=? AND edge_type IN (" + types + ")", jobId); }
        catch (Exception ignored) {}
    }

    // ==================== 助手 ====================

    private Map<String, Object> edge(long srcDs, String srcSchema, String srcTable,
                                     long tgtDs, String tgtSchema, String tgtTable,
                                     String edgeType, long jobId, String jobName) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("src_ds_id", srcDs);
        e.put("src_schema", srcSchema == null ? "" : srcSchema);
        e.put("src_table", srcTable == null ? "" : srcTable);
        e.put("tgt_ds_id", tgtDs);
        e.put("tgt_schema", tgtSchema == null ? "" : tgtSchema);
        e.put("tgt_table", tgtTable == null ? "" : tgtTable);
        e.put("edge_type", edgeType);
        e.put("job_id", jobId);
        e.put("job_name", jobName == null ? "" : jobName);
        return e;
    }

    private void addIf(List<Map<String, Object>> out, Map<String, Object> e) {
        if (e == null) return;
        if (str(e.get("src_table")).isEmpty() || str(e.get("tgt_table")).isEmpty()) return;
        out.add(e);
    }

    private List<Map<String, Object>> safeList(String sql, Object... args) {
        try { return args.length == 0 ? jdbc.queryForList(sql) : jdbc.queryForList(sql, args); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> l = safeList(sql, args);
        return l.isEmpty() ? null : l.get(0);
    }

    private static boolean eq(String a, String b) { return a != null && a.equalsIgnoreCase(b); }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
