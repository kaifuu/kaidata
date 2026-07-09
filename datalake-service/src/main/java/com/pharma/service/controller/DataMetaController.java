package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.meta.TableExtractor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;

/**
 * 元数据 [SYS_ADMIN]：技术元数据浏览/同步 + 库表左树 + 补录保存 + 填充度 + 版本对比/应用/强制更新 +
 * 字段映射 + 全文检索。血缘/影响/全链分析接口见 data-gov/meta/lineage|impact|fulllink（阶段E/F）。
 */
@RestController
@RequestMapping("/api/data-gov/meta")
@CrossOrigin(origins = "*")
public class DataMetaController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    // ==================== 列表 / 详情 / 同步 ====================

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long dsId,
                                          @RequestParam(required = false) String kw) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id, ds_id, schema_name, table_name, comment, cn_name, " +
                "layer_code, subject_id, fill_percent, mount_status, current_version, synced_time " +
                "FROM meta.gov_meta_table WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dsId != null) { sql.append(" AND ds_id=?"); args.add(dsId); }
        if (kw != null && !kw.isEmpty()) {
            sql.append(" AND (table_name LIKE ? OR cn_name LIKE ? OR comment LIKE ?)");
            String p = "%" + kw + "%"; args.add(p); args.add(p); args.add(p);
        }
        sql.append(" ORDER BY ds_id, schema_name, table_name LIMIT 500");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> m = jdbc.queryForMap("SELECT id, ds_id, schema_name, table_name, comment, columns_json, " +
                "row_count, synced_time, cn_name, dept, app_system, resource_attr, layer_code, subject_id, share_type, " +
                "admin_owner, admin_contact, data_category, security_level, mask_rule_id, alert_def_id, fill_percent, " +
                "mount_status, current_version, description, update_time FROM meta.gov_meta_table WHERE id=?", id);
        m.put("max_version", maxVersion(id));
        return m;
    }

    /** 手动全量同步（保留旧入口；建议改用「元数据采集」任务以获得版本化）。 */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds = loader.load(dsId);
        DataSourceAdapter a = registry.adapter(ds.type);
        DataSource pool = registry.getPool(ds);
        List<Map<String, Object>> tables = a.listTables(pool, null);
        int count = 0;
        for (Map<String, Object> t : tables) {
            String schema = str(t.get("schema_name"));
            String table = str(t.get("name"));
            if (table.isEmpty()) continue;
            try {
                String[] sp = com.pharma.service.access.util.SqlBuilder.splitTable(schema.isEmpty() ? table : schema + "." + table);
                List<Map<String, Object>> cols = a.describeTable(pool, sp[0], sp[1]);
                String colsJson = json.writeValueAsString(cols);
                upsertMeta(dsId, schema, table, str(t.get("comment")), colsJson);
                count++;
            } catch (Exception ignored) {}
        }
        return Map.of("success", true, "synced", count);
    }

    // ==================== 库表左树 ====================

    @GetMapping("/tree")
    public List<Map<String, Object>> tree(@RequestParam(required = false) Long dsId) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id, ds_id, schema_name, table_name, cn_name FROM meta.gov_meta_table WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dsId != null) { sql.append(" AND ds_id=?"); args.add(dsId); }
        sql.append(" ORDER BY ds_id, schema_name, table_name LIMIT 2000");
        Map<Long, String> dsName = dsNameMap();
        Map<Long, List<Map<String, Object>>> byDs = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql.toString(), args.toArray())) {
            long did = lng(r.get("ds_id"));
            String sch = str(r.get("schema_name"));
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("id", lng(r.get("id")));
            leaf.put("label", (sch.isEmpty() ? "" : sch + ".") + str(r.get("table_name")));
            leaf.put("type", "table");
            leaf.put("table", str(r.get("table_name")));
            leaf.put("schema", sch);
            leaf.put("cn_name", str(r.get("cn_name")));
            byDs.computeIfAbsent(did, k -> new ArrayList<>()).add(leaf);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> e : byDs.entrySet()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", "ds_" + e.getKey());
            n.put("label", dsName.getOrDefault(e.getKey(), "ds" + e.getKey()));
            n.put("type", "ds");
            n.put("ds_id", e.getKey());
            n.put("children", e.getValue());
            out.add(n);
        }
        return out;
    }

    // ==================== 补录保存 / 填充度 ====================

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        if (id == 0) throw new RuntimeException("补录需指定已存在的库表元数据 id");
        int fill = calcFill(b);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("UPDATE meta.gov_meta_table SET cn_name=?, dept=?, app_system=?, resource_attr=?, layer_code=?, " +
                        "subject_id=?, share_type=?, admin_owner=?, admin_contact=?, data_category=?, security_level=?, " +
                        "mask_rule_id=?, alert_def_id=?, description=?, fill_percent=?, update_time=? WHERE id=?",
                str(b.get("cn_name")), str(b.get("dept")), str(b.get("app_system")), str(b.get("resource_attr")),
                str(b.get("layer_code")), lng(b.get("subject_id")), str(b.get("share_type")), str(b.get("admin_owner")),
                str(b.get("admin_contact")), str(b.get("data_category")), str(b.get("security_level")),
                lng(b.get("mask_rule_id")), lng(b.get("alert_def_id")), str(b.get("description")), fill, now, id);
        return Map.of("success", true, "fill_percent", fill);
    }

    @GetMapping("/fill")
    public Map<String, Object> fill(@RequestParam long metaId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForMap("SELECT cn_name, dept, subject_id, admin_owner, data_category, security_level, " +
                "description, fill_percent FROM meta.gov_meta_table WHERE id=?", metaId);
    }

    // ==================== 版本对比 / 应用 / 强制更新 ====================

    @GetMapping("/version/list")
    public List<Map<String, Object>> versionList(@RequestParam long metaId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, version_n, change_type, change_detail, source, created_time " +
                "FROM meta.gov_meta_version WHERE meta_id=? ORDER BY version_n DESC", metaId);
    }

    @GetMapping("/version/compare")
    public Map<String, Object> versionCompare(@RequestParam long metaId, @RequestParam int v1, @RequestParam int v2) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", versionRow(metaId, v1));
        out.put("v2", versionRow(metaId, v2));
        return out;
    }

    @PostMapping("/version/apply")
    public Map<String, Object> versionApply(@RequestParam long metaId, @RequestParam int versionN) {
        Authz.require(Authz.SYS_ADMIN);
        Integer max = maxVersion(metaId);
        if (max == null || versionN > max) throw new RuntimeException("版本 " + versionN + " 不存在");
        String colsJson = str(versionRow(metaId, versionN).get("columns_json"));
        jdbc.update("UPDATE meta.gov_meta_table SET columns_json=?, current_version=?, update_time=? WHERE id=?",
                colsJson, versionN, new Timestamp(System.currentTimeMillis()), metaId);
        return Map.of("success", true, "current_version", versionN);
    }

    /** 强制更新：立即重新 describe → 插新版本 → 直接应用（跳过 pending 等待）。 */
    @PostMapping("/version/force")
    public Map<String, Object> versionForce(@RequestParam long metaId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> m = jdbc.queryForMap("SELECT ds_id, schema_name, table_name FROM meta.gov_meta_table WHERE id=?", metaId);
        long dsId = lng(m.get("ds_id"));
        String schema = str(m.get("schema_name"));
        String table = str(m.get("table_name"));
        DataSourceDescriptor ds = loader.load(dsId);
        DataSourceAdapter a = registry.adapter(ds.type);
        DataSource pool = registry.getPool(ds);
        String[] sp = com.pharma.service.access.util.SqlBuilder.splitTable(schema.isEmpty() ? table : schema + "." + table);
        String colsJson;
        try {
            colsJson = json.writeValueAsString(a.describeTable(pool, sp[0], sp[1]));
        } catch (Exception e) {
            throw new RuntimeException("读取表结构失败: " + e.getMessage());
        }
        Integer prevMax = maxVersion(metaId);
        int newVer = (prevMax == null ? 0 : prevMax) + 1;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("INSERT INTO meta.gov_meta_version(id, meta_id, version_n, columns_json, change_type, " +
                        "change_detail, source, created_time) VALUES (?,?,?,?,?,?,?,?)",
                System.currentTimeMillis() + newVer, metaId, newVer, colsJson, "MODIFIED", "FORCE", "COLLECT", now);
        jdbc.update("UPDATE meta.gov_meta_table SET columns_json=?, current_version=?, synced_time=?, update_time=? WHERE id=?",
                colsJson, newVer, now, now, metaId);
        return Map.of("success", true, "current_version", newVer);
    }

    // ==================== 字段映射 ====================

    @GetMapping("/fieldmap")
    public List<Map<String, Object>> fieldmap(@RequestParam long metaId) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, meta_id, logical_field, src_ds_id, src_schema, src_table, src_field, " +
                "job_type, job_id FROM meta.gov_meta_field_map WHERE meta_id=?", metaId);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/fieldmap")
    public Map<String, Object> saveFieldmap(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long metaId = lng(b.get("meta_id"));
        jdbc.update("DELETE FROM meta.gov_meta_field_map WHERE meta_id=?", metaId);
        for (Object o : (List<Object>) b.getOrDefault("rows", List.of())) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> r = (Map<String, Object>) o;
            jdbc.update("INSERT INTO meta.gov_meta_field_map(id, meta_id, logical_field, src_ds_id, src_schema, " +
                            "src_table, src_field, job_type, job_id, create_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), metaId, str(r.get("logical_field")),
                    lng(r.get("src_ds_id")), str(r.get("src_schema")), str(r.get("src_table")), str(r.get("src_field")),
                    str(r.get("job_type")), lng(r.get("job_id")), new Timestamp(System.currentTimeMillis()));
        }
        return Map.of("success", true);
    }

    @DeleteMapping("/fieldmap")
    public Map<String, Object> deleteFieldmap(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_meta_field_map WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 全文检索 ====================

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String kw) {
        Authz.require(Authz.SYS_ADMIN);
        String p = "%" + kw + "%";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", jdbc.queryForList("SELECT id, ds_id, schema_name, table_name, cn_name, comment " +
                "FROM meta.gov_meta_table WHERE table_name LIKE ? OR cn_name LIKE ? OR comment LIKE ? OR description LIKE ? LIMIT 50", p, p, p, p));
        out.put("fields", searchFields(kw));
        try {
            out.put("apis", jdbc.queryForList("SELECT s.id AS service_id, s.name, s.code, m.cn_name " +
                    "FROM meta.data_service s LEFT JOIN meta.gov_meta_api m ON m.service_id=s.id " +
                    "WHERE s.name LIKE ? OR s.code LIKE ? OR m.cn_name LIKE ? LIMIT 50", p, p, p));
        } catch (Exception e) { out.put("apis", List.of()); }
        try {
            out.put("files", jdbc.queryForList("SELECT f.id, f.path, f.cn_name, st.name AS store_name " +
                    "FROM meta.gov_meta_file f LEFT JOIN meta.ing_filestore st ON st.id=f.store_id " +
                    "WHERE f.path LIKE ? OR f.cn_name LIKE ? LIMIT 50", p, p));
        } catch (Exception e) { out.put("files", List.of()); }
        return out;
    }

    private List<Map<String, Object>> searchFields(String kw) {
        String lower = kw.toLowerCase();
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, ds_id, schema_name, table_name, columns_json FROM meta.gov_meta_table LIMIT 2000");
        for (Map<String, Object> r : rows) {
            try {
                for (var n : json.readTree(str(r.get("columns_json")))) {
                    String name = n.has("name") ? n.get("name").asText() : "";
                    String cmt = n.has("comment") ? n.get("comment").asText() : "";
                    if (name.toLowerCase().contains(lower) || cmt.toLowerCase().contains(lower)) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("table_id", lng(r.get("id")));
                        f.put("schema", str(r.get("schema_name")));
                        f.put("table", str(r.get("table_name")));
                        f.put("field", name);
                        f.put("comment", cmt);
                        fields.add(f);
                        if (fields.size() >= 50) return fields;
                    }
                }
            } catch (Exception ignored) {}
        }
        return fields;
    }

    // ==================== 血缘 / 影响 / 全链 ====================

    /** 上游血缘：谁写入此表（离线接入 target / 实时管道 target 解析 source_query）。 */
    @GetMapping("/lineage")
    public Map<String, Object> lineage(@RequestParam long dsId, @RequestParam(required = false) String schema, @RequestParam String table) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        long center = addNode(nodes, "table", tableKey(schema, table), dsId, hasPending(table));
        for (Map<String, Object> j : safeList("SELECT id, name, source_ds_id, source_table FROM meta.ing_offline_job WHERE target_table=?", table)) {
            String src = str(j.get("source_table"));
            if (src.isEmpty()) continue;
            long s = addNode(nodes, "table", tableKey("", src), lng(j.get("source_ds_id")), false);
            links.add(link(s, center, "OFFLINE", str(j.get("name"))));
        }
        for (Map<String, Object> j : safeList("SELECT id, name, source_ds_id, source_query FROM meta.ing_stream_job WHERE target_table=?", table)) {
            for (String src : TableExtractor.parse(str(j.get("source_query")))) {
                long s = addNode(nodes, "table", tableKey("", src), lng(j.get("source_ds_id")), false);
                links.add(link(s, center, "STREAM", str(j.get("name"))));
            }
        }
        return graph(nodes, links);
    }

    /** 下游影响：此表流向哪些表/外部（离线/实时/接出 的源）。 */
    @GetMapping("/impact")
    public Map<String, Object> impact(@RequestParam long dsId, @RequestParam(required = false) String schema, @RequestParam String table) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        long center = addNode(nodes, "table", tableKey(schema, table), dsId, false);
        for (Map<String, Object> j : safeList("SELECT id, name, target_table FROM meta.ing_offline_job WHERE source_table=?", table)) {
            String t = str(j.get("target_table"));
            if (t.isEmpty()) continue;
            long n = addNode(nodes, "table", tableKey("", t), dsId, false);
            links.add(link(center, n, "OFFLINE", str(j.get("name"))));
        }
        for (Map<String, Object> j : safeList("SELECT id, name, target_table, source_query FROM meta.ing_stream_job", null)) {
            if (TableExtractor.parse(str(j.get("source_query"))).stream().noneMatch(s -> s.equalsIgnoreCase(table))) continue;
            String t = str(j.get("target_table"));
            if (t.isEmpty()) continue;
            long n = addNode(nodes, "table", tableKey("", t), dsId, false);
            links.add(link(center, n, "STREAM", str(j.get("name"))));
        }
        for (Map<String, Object> j : safeList("SELECT id, name, target_type, source_query FROM meta.dev_export", null)) {
            if (TableExtractor.parse(str(j.get("source_query"))).stream().noneMatch(s -> s.equalsIgnoreCase(table))) continue;
            long n = addNode(nodes, "external", "EXPORT:" + str(j.get("target_type")), dsId, false);
            links.add(link(center, n, "EXPORT", str(j.get("name"))));
        }
        return graph(nodes, links);
    }

    /** 全链图谱：数据源 → 表 → 表间流转（该 ds 维度）；expandFields=true 叠加字段级映射边。 */
    @GetMapping("/fulllink")
    public Map<String, Object> fulllink(@RequestParam long dsId, @RequestParam(defaultValue = "false") boolean expandFields) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Map<Long, String> dsName = dsNameMap();
        long dsNode = addNode(nodes, "ds", dsName.getOrDefault(dsId, "ds" + dsId), dsId, false);
        List<Map<String, Object>> metas = jdbc.queryForList("SELECT id, schema_name, table_name FROM meta.gov_meta_table WHERE ds_id=? LIMIT 300", dsId);
        Set<String> tblSet = new HashSet<>();
        Map<String, Long> tableNodes = new HashMap<>();
        for (Map<String, Object> m : metas) {
            String tbl = str(m.get("table_name"));
            tblSet.add(tbl);
            long tn = addNode(nodes, "table", tableKey(str(m.get("schema_name")), tbl), dsId, hasPending(tbl));
            tableNodes.put(tbl, tn);
            links.add(link(dsNode, tn, "CONTAIN", ""));
        }
        if (!tblSet.isEmpty()) {
            String inList = "'" + String.join("','", tblSet) + "'";
            for (Map<String, Object> j : safeList("SELECT id, name, source_table, target_table FROM meta.ing_offline_job WHERE target_table IN (" + inList + ")", null)) {
                String s = str(j.get("source_table")), t = str(j.get("target_table"));
                if (s.isEmpty() || t.isEmpty()) continue;
                long sn = tableNodes.containsKey(s) ? tableNodes.get(s) : addNode(nodes, "table", tableKey("", s), dsId, false);
                Long tn = tableNodes.get(t);
                if (tn == null) { tn = addNode(nodes, "table", tableKey("", t), dsId, false); tableNodes.put(t, tn); }
                links.add(link(sn, tn, "OFFLINE", str(j.get("name"))));
            }
        }
        if (expandFields) {
            for (Map<String, Object> m : metas) {
                Long tnode = tableNodes.get(str(m.get("table_name")));
                if (tnode == null) continue;
                for (Map<String, Object> fm : safeList("SELECT logical_field, src_table, src_field FROM meta.gov_meta_field_map WHERE meta_id=?", lng(m.get("id")))) {
                    long fn = addNode(nodes, "field", str(fm.get("logical_field")), dsId, false);
                    links.add(link(tnode, fn, "FIELD", str(fm.get("src_table")) + "." + str(fm.get("src_field"))));
                }
            }
        }
        return graph(nodes, links);
    }

    // ---- 血缘助手 ----
    private long addNode(List<Map<String, Object>> nodes, String category, String label, long dsId, boolean pending) {
        for (Map<String, Object> n : nodes) {
            if (label.equals(n.get("label")) && category.equals(n.get("category"))) return ((Number) n.get("id")).longValue();
        }
        long id = nodes.size() + 1;
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("label", label);
        n.put("category", category);
        n.put("ds_id", dsId);
        n.put("pending", pending);
        nodes.add(n);
        return id;
    }

    private Map<String, Object> link(long from, long to, String type, String name) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", from);
        e.put("target", to);
        e.put("jobType", type);
        e.put("jobName", name);
        return e;
    }

    private Map<String, Object> graph(List<Map<String, Object>> nodes, List<Map<String, Object>> links) {
        return Map.of("nodes", nodes, "links", links);
    }

    private List<Map<String, Object>> safeList(String sql, Object arg) {
        try { return arg == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, arg); }
        catch (Exception e) { return List.of(); }
    }

    private static String tableKey(String schema, String table) {
        return (schema == null || schema.isEmpty() ? "" : schema + ".") + table;
    }

    private boolean hasPending(String table) {
        try {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT id, current_version FROM meta.gov_meta_table WHERE table_name=? LIMIT 1", table);
            if (r.isEmpty()) return false;
            int cur = r.get(0).get("current_version") == null ? 0 : ((Number) r.get(0).get("current_version")).intValue();
            Integer max = maxVersion(lng(r.get(0).get("id")));
            return max != null && max > cur;
        } catch (Exception e) { return false; }
    }

    // ==================== 助手 ====================

    private void upsertMeta(long dsId, String schema, String table, String comment, String colsJson) {
        List<Map<String, Object>> exist = jdbc.queryForList(
                "SELECT id FROM meta.gov_meta_table WHERE ds_id=? AND schema_name=? AND table_name=?", dsId, schema, table);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (exist.isEmpty()) {
            jdbc.update("INSERT INTO meta.gov_meta_table(id, ds_id, schema_name, table_name, comment, columns_json, " +
                            "row_count, synced_time, current_version, mount_status, fill_percent) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis() + (long) (Math.random() * 1000), dsId, schema, table, comment, colsJson, 0L, now, 1, "NONE", 0);
        } else {
            jdbc.update("UPDATE meta.gov_meta_table SET comment=?, columns_json=?, synced_time=? WHERE id=?",
                    comment, colsJson, now, ((Number) exist.get(0).get("id")).longValue());
        }
    }

    private Integer maxVersion(long metaId) {
        try {
            return jdbc.queryForObject("SELECT MAX(version_n) FROM meta.gov_meta_version WHERE meta_id=?", Integer.class, metaId);
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> versionRow(long metaId, int ver) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version_n, columns_json, change_detail, created_time FROM meta.gov_meta_version " +
                        "WHERE meta_id=? AND version_n=? ORDER BY id DESC LIMIT 1", metaId, ver);
        return rows.isEmpty() ? Map.of("version_n", ver, "columns_json", "[]") : rows.get(0);
    }

    private Map<Long, String> dsNameMap() {
        Map<Long, String> m = new HashMap<>();
        try { for (Map<String, Object> r : jdbc.queryForList("SELECT id, name FROM meta.ing_datasource")) m.put(lng(r.get("id")), str(r.get("name"))); }
        catch (Exception ignored) {}
        return m;
    }

    private int calcFill(Map<String, Object> b) {
        String[] req = {"cn_name", "dept", "subject_id", "admin_owner", "data_category", "security_level", "description"};
        int filled = 0;
        for (String k : req) {
            Object v = b.get(k);
            if ("subject_id".equals(k)) { if (lng(v) != 0) filled++; }
            else { if (v != null && !String.valueOf(v).trim().isEmpty()) filled++; }
        }
        return filled * 100 / req.length;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
