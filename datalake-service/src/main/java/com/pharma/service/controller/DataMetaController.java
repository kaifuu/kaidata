package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.meta.MetaCollectExecutor;
import com.pharma.service.access.meta.TableExtractor;
import com.pharma.service.access.profile.VersionDiffer;
import com.pharma.service.security.Authz;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
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
    @Autowired private MetaCollectExecutor metaCollectExecutor;
    private final ObjectMapper json = new ObjectMapper();

    // ==================== 列表 / 详情 / 同步 ====================

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(required = false) Long dsId,
                                          @RequestParam(required = false) String kw,
                                          @RequestParam(required = false) Long subjectId) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT t.id, t.ds_id, t.schema_name, t.table_name, t.comment, t.cn_name, " +
                "t.layer_code, t.subject_id, s.name AS subject_name, t.fill_percent, t.mount_status, t.current_version, " +
                "t.synced_time, t.columns_json FROM meta.gov_meta_table t " +
                "LEFT JOIN meta.gov_subject s ON s.id=t.subject_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (dsId != null) { sql.append(" AND t.ds_id=?"); args.add(dsId); }
        if (subjectId != null && subjectId > 0) { sql.append(" AND t.subject_id=?"); args.add(subjectId); }
        if (kw != null && !kw.isEmpty()) {
            sql.append(" AND (t.table_name LIKE ? OR t.cn_name LIKE ? OR t.comment LIKE ?)");
            String p = "%" + kw + "%"; args.add(p); args.add(p); args.add(p);
        }
        sql.append(" ORDER BY t.ds_id, t.schema_name, t.table_name LIMIT 500");
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
                metaCollectExecutor.upsertTable(dsId, schema, table, colsJson);
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

    // ==================== 补录保存 / 新建登记 / 删除 / 结构编辑 ====================

    private static final String[] BIZ_COLS = {"cn_name", "dept", "app_system", "resource_attr", "layer_code",
            "subject_id", "share_type", "admin_owner", "admin_contact", "data_category", "security_level",
            "mask_rule_id", "alert_def_id", "description"};

    /**
     * 补录保存（/fill/import 复用）。id==0 → 新建登记（自动采集覆盖不到的表：外部系统/未纳管数据源）；
     * id>0 → 先读全行合并再整体写回——修复历史毁数据 bug：部分 payload 会把未传的业务列抹成空。
     */
    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        return doSave(b);
    }

    private Map<String, Object> doSave(Map<String, Object> b) {
        long id = lng(b.get("id"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (id == 0) {
            long dsId = lng(b.get("ds_id"));
            String schema = str(b.get("schema_name")).trim();
            String table = str(b.get("table_name")).trim();
            if (table.isEmpty()) throw new IllegalArgumentException("表名必填");
            List<Map<String, Object>> dup = jdbc.queryForList(
                    "SELECT id FROM meta.gov_meta_table WHERE ds_id=? AND schema_name=? AND table_name=?", dsId, schema, table);
            if (!dup.isEmpty())
                throw new IllegalArgumentException("该表已登记（#" + lng(dup.get(0).get("id")) + "），请直接补录，勿重复登记");
            long metaId = System.currentTimeMillis() + (long) (Math.random() * 1000);
            jdbc.update("INSERT INTO meta.gov_meta_table(id, ds_id, schema_name, table_name, comment, columns_json, " +
                            "row_count, synced_time, fill_percent, current_version, mount_status, update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    metaId, dsId, schema, table, str(b.get("comment")), "[]", 0L, now, calcFill(b), 1, "NONE", now);
            jdbc.update("INSERT INTO meta.gov_meta_version(id, meta_id, version_n, columns_json, change_type, " +
                            "change_detail, source, created_time) VALUES (?,?,?,?,?,?,?,?)",
                    metaId + 1, metaId, 1, "[]", "INIT", "", "MANUAL", now);
            return Map.of("success", true, "id", metaId, "fill_percent", calcFill(b));
        }
        Map<String, Object> cur = jdbc.queryForMap("SELECT " + String.join(", ", BIZ_COLS) +
                " FROM meta.gov_meta_table WHERE id=?", id);
        for (String k : BIZ_COLS) if (b.containsKey(k)) cur.put(k, b.get(k));
        int fill = calcFill(cur);
        jdbc.update("UPDATE meta.gov_meta_table SET cn_name=?, dept=?, app_system=?, resource_attr=?, layer_code=?, " +
                        "subject_id=?, share_type=?, admin_owner=?, admin_contact=?, data_category=?, security_level=?, " +
                        "mask_rule_id=?, alert_def_id=?, description=?, fill_percent=?, update_time=? WHERE id=?",
                str(cur.get("cn_name")), str(cur.get("dept")), str(cur.get("app_system")), str(cur.get("resource_attr")),
                str(cur.get("layer_code")), lng(cur.get("subject_id")), str(cur.get("share_type")), str(cur.get("admin_owner")),
                str(cur.get("admin_contact")), str(cur.get("data_category")), str(cur.get("security_level")),
                lng(cur.get("mask_rule_id")), lng(cur.get("alert_def_id")), str(cur.get("description")), fill, now, id);
        return Map.of("success", true, "id", id, "fill_percent", fill);
    }

    /** 删除表元数据（连带版本）；被资产挂载的禁止删（防资产悬空，须先解绑）。 */
    @DeleteMapping("")
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT schema_name, table_name FROM meta.gov_meta_table WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("元数据不存在");
        long mounted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meta.asset WHERE source_type='meta_table' AND source_id=?", Long.class, id);
        if (mounted > 0)
            throw new IllegalArgumentException("已有 " + mounted + " 个资产挂载此元数据，请先在「资产编目」解绑");
        jdbc.update("DELETE FROM meta.gov_meta_version WHERE meta_id=?", id);
        jdbc.update("DELETE FROM meta.gov_meta_field_map WHERE meta_id=?", id);
        jdbc.update("DELETE FROM meta.gov_meta_table WHERE id=?", id);
        return Map.of("success", true, "name",
                str(rows.get(0).get("schema_name")) + "." + str(rows.get(0).get("table_name")));
    }

    /**
     * 结构（字段清单）手工维护：登记 source='MANUAL' 的新版本并立即生效（现行结构永不被采集静默覆盖——
     * 采集/探查结构变化只记待生效版本，人工「应用版本」后才替换）。
     */
    @PostMapping("/columns")
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveColumns(@RequestBody Map<String, Object> b) throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT columns_json, current_version FROM meta.gov_meta_table WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("元数据不存在");
        List<Map<String, Object>> cols = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object o : (List<Object>) b.getOrDefault("columns", List.of())) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> c = (Map<String, Object>) o;
            String name = str(c.get("name")).trim();
            if (name.isEmpty()) throw new IllegalArgumentException("字段名不能为空");
            if (!names.add(name.toLowerCase())) throw new IllegalArgumentException("字段名重复：" + name);
            Map<String, Object> nc = new LinkedHashMap<>();
            nc.put("name", name);
            nc.put("type", str(c.get("type")));
            nc.put("comment", str(c.get("comment")));
            cols.add(nc);
        }
        String colsJson = json.writeValueAsString(cols);
        String prevJson = str(rows.get(0).get("columns_json"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int curVer = rows.get(0).get("current_version") == null ? 0 : ((Number) rows.get(0).get("current_version")).intValue();
        VersionDiffer.Diff diff = prevJson.isEmpty() ? new VersionDiffer.Diff()
                : VersionDiffer.diff(toTypeMap(prevJson), toTypeMap(colsJson));
        int newVer = curVer;
        if (diff.hasChange()) {
            Integer prevMax = maxVersion(id);
            newVer = (prevMax == null ? 0 : prevMax) + 1;
            String changeDetail = "+[" + String.join(",", diff.added) + "] -[" + String.join(",", diff.removed) +
                    "] ~[" + String.join(";", diff.typeChanged) + "]";
            jdbc.update("INSERT INTO meta.gov_meta_version(id, meta_id, version_n, columns_json, change_type, " +
                            "change_detail, source, created_time) VALUES (?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis() + newVer, id, newVer, colsJson, "手工修正", trimS(changeDetail, 2000), "MANUAL", now);
        }
        jdbc.update("UPDATE meta.gov_meta_table SET columns_json=?, current_version=?, update_time=? WHERE id=?",
                colsJson, newVer, now, id);
        Object cmt = b.get("comment");
        if (cmt != null) jdbc.update("UPDATE meta.gov_meta_table SET comment=? WHERE id=?", str(cmt), id);
        return Map.of("success", true, "version", newVer, "columns", cols.size());
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

    // ==================== 补录统计 / 待补录清单（补录工作台 MetaFill.vue） ====================

    @GetMapping("/fill/stats")
    public Map<String, Object> fillStats() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> table = fillAgg("meta.gov_meta_table");
        Map<String, Object> api = fillAgg("meta.gov_meta_api");
        Map<String, Object> file = fillAgg("meta.gov_meta_file");
        long tTotal = lng(table.get("total")) + lng(api.get("total")) + lng(file.get("total"));
        long tFilled = lng(table.get("filled")) + lng(api.get("filled")) + lng(file.get("filled"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", tTotal == 0 ? 0 : Math.round(tFilled * 100.0 / tTotal));
        out.put("table", table);
        out.put("api", api);
        out.put("file", file);
        return out;
    }

    @GetMapping("/fill/list")
    public Map<String, Object> fillList(@RequestParam(defaultValue = "table") String type,
                                        @RequestParam(defaultValue = "filling") String status,
                                        @RequestParam(required = false) String kw,
                                        @RequestParam(required = false) Long dsId,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Authz.require(Authz.SYS_ADMIN);
        List<Object> args = new ArrayList<>();
        boolean filled = "filled".equalsIgnoreCase(status);
        String fillCond = filled ? "fill_percent>=100" : "(fill_percent<100 OR fill_percent IS NULL)";
        String tFillCond = filled ? "t.fill_percent>=100" : "(t.fill_percent<100 OR t.fill_percent IS NULL)";
        List<Map<String, Object>> records;
        long total;
        int offset = Math.max(0, (page - 1) * size);
        if ("table".equalsIgnoreCase(type) || type == null || type.isEmpty()) {
            // 库表：全业务列富化（数据源名/主题域名/待生效版本数），供清单展示与补录抽屉回读
            StringBuilder where = new StringBuilder(" WHERE " + tFillCond);
            if (kw != null && !kw.isEmpty()) {
                where.append(" AND (t.table_name LIKE ? OR t.cn_name LIKE ?)"); args.add("%" + kw + "%"); args.add("%" + kw + "%");
            }
            if (dsId != null) { where.append(" AND t.ds_id=?"); args.add(dsId); }
            total = cntFill("SELECT COUNT(*) FROM meta.gov_meta_table t" + where, args);
            records = jdbc.queryForList(
                    "SELECT t.id, t.ds_id, t.schema_name, t.table_name AS name, t.table_name, t.cn_name, t.dept, " +
                            "t.admin_owner, t.data_category, t.security_level, t.description, t.layer_code, t.subject_id, " +
                            "s.name AS subject_name, d.name AS ds_name, t.fill_percent, t.synced_time, t.mount_status, " +
                            "t.current_version, CASE WHEN v.maxv IS NULL OR v.maxv <= COALESCE(t.current_version, 0) THEN 0 " +
                            "ELSE v.maxv - COALESCE(t.current_version, 0) END AS pending_versions " +
                            "FROM meta.gov_meta_table t " +
                            "LEFT JOIN meta.gov_subject s ON s.id = t.subject_id " +
                            "LEFT JOIN meta.ing_datasource d ON d.id = t.ds_id " +
                            "LEFT JOIN (SELECT meta_id, MAX(version_n) AS maxv FROM meta.gov_meta_version GROUP BY meta_id) v ON v.meta_id = t.id" +
                            where + " ORDER BY t.fill_percent ASC LIMIT " + offset + "," + size, args.toArray());
        } else {
            String table = "api".equalsIgnoreCase(type) ? "meta.gov_meta_api" : "meta.gov_meta_file";
            String nameCol = "api".equalsIgnoreCase(type) ? "cn_name" : "path";
            String idCol = "api".equalsIgnoreCase(type) ? "service_id" : "id";
            StringBuilder where = new StringBuilder(" WHERE " + fillCond);
            if (kw != null && !kw.isEmpty()) { where.append(" AND ").append(nameCol).append(" LIKE ?"); args.add("%" + kw + "%"); }
            total = cntFill("SELECT COUNT(*) FROM " + table + where, args);
            records = jdbc.queryForList("SELECT " + idCol + " AS id, " + nameCol + " AS name, fill_percent FROM " + table
                    + where + " ORDER BY fill_percent ASC LIMIT " + offset + "," + size, args.toArray());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        return out;
    }

    private Map<String, Object> fillAgg(String table) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Double avg = jdbc.queryForObject("SELECT AVG(fill_percent) FROM " + table, Double.class);
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            Long filled = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE fill_percent>=100", Long.class);
            m.put("avg", avg == null ? 0 : Math.round(avg));
            m.put("total", total == null ? 0 : total);
            m.put("filled", filled == null ? 0 : filled);
            m.put("filling", (total == null ? 0 : total) - (filled == null ? 0 : filled));
        } catch (Exception e) {
            m.put("avg", 0); m.put("total", 0); m.put("filled", 0); m.put("filling", 0);
        }
        return m;
    }

    private long cntFill(String sql, List<Object> args) {
        try { return args.isEmpty() ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, args.toArray()); }
        catch (Exception e) { return 0; }
    }

    // ==================== Excel 批量补录 ====================

    private static final String[] FILL_HEADERS = {"库名", "表名", "中文名", "所属部门", "应用系统", "资源管理员",
            "联系方式", "数据分类", "安全级别", "层级", "主题域编码", "共享类型", "业务描述"};

    /** 补录导入模板（表头 + 示例行）。 */
    @GetMapping("/fill/import-template")
    public ResponseEntity<byte[]> fillImportTemplate() throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("补录导入");
            Row hr = sheet.createRow(0);
            for (int i = 0; i < FILL_HEADERS.length; i++) {
                hr.createCell(i).setCellValue(FILL_HEADERS[i]);
                sheet.setColumnWidth(i, 16 * 256);
            }
            Row ex = sheet.createRow(1);
            String[] vals = {"ods", "dem_user", "用户信息表", "数据部", "数仓平台", "admin",
                    "admin@demo.cn", "业务数据", "", "ods", "trade", "内部", "核心用户主表"};
            for (int i = 0; i < vals.length; i++) ex.createCell(i).setCellValue(vals[i]);
            wb.write(out);
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode("元数据补录导入模板.xlsx", "UTF-8").replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(out.toByteArray());
    }

    /** Excel 批量补录：按 库名+表名 匹配已登记表回填业务属性（走 /save 同一合并逻辑，不抹未填列）。 */
    @PostMapping("/fill/import")
    public Map<String, Object> fillImport(@RequestParam("file") MultipartFile file) throws Exception {
        Authz.require(Authz.SYS_ADMIN);
        Set<String> secCodes = codeSet("SELECT code FROM meta.sec_standard");
        Set<String> layerCodes = codeSet("SELECT code FROM meta.gov_layer");
        Map<String, Long> subjectByCode = new HashMap<>();
        for (Map<String, Object> s : jdbc.queryForList("SELECT id, code FROM meta.gov_subject"))
            subjectByCode.put(str(s.get("code")).toLowerCase(), lng(s.get("id")));
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
            XSSFSheet sheet = wb.getSheetAt(0);
            Row hr = sheet.getRow(0);
            if (hr == null) throw new IllegalArgumentException("Excel 为空");
            Map<String, Integer> colIdx = new HashMap<>();
            for (int i = 0; i < FILL_HEADERS.length; i++) colIdx.put(FILL_HEADERS[i], -1);
            for (Cell c : hr) {
                if (c.getCellType() == CellType.STRING && colIdx.containsKey(c.getStringCellValue().trim()))
                    colIdx.put(c.getStringCellValue().trim(), c.getColumnIndex());
            }
            if (colIdx.get("库名") < 0 || colIdx.get("表名") < 0)
                throw new IllegalArgumentException("表头必须包含「库名」「表名」列（请使用导入模板）");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String schema = cellStr(row, colIdx.get("库名"));
                String table = cellStr(row, colIdx.get("表名"));
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("schema", schema);
                res.put("table", table);
                try {
                    if (table.isEmpty()) { res.put("status", "skip"); res.put("msg", "表名为空，跳过"); results.add(res); continue; }
                    String cnName = cellStr(row, colIdx.get("中文名"));
                    String sec = cellStr(row, colIdx.get("安全级别"));
                    String layer = cellStr(row, colIdx.get("层级"));
                    String subjCode = cellStr(row, colIdx.get("主题域编码"));
                    if (!sec.isEmpty() && !secCodes.contains(sec))
                        throw new IllegalArgumentException("安全级别编码不存在：" + sec + "（有效值见「安全标准」）");
                    if (!layer.isEmpty() && !layerCodes.contains(layer))
                        throw new IllegalArgumentException("层级不存在：" + layer);
                    Long subjectId = null;
                    if (!subjCode.isEmpty()) {
                        subjectId = subjectByCode.get(subjCode.toLowerCase());
                        if (subjectId == null) throw new IllegalArgumentException("主题域编码不存在：" + subjCode);
                    }
                    List<Map<String, Object>> hits = jdbc.queryForList(
                            "SELECT id FROM meta.gov_meta_table WHERE schema_name=? AND table_name=?", schema, table);
                    if (hits.isEmpty()) throw new IllegalArgumentException("未登记该表（请先在补录工作台「新增登记」）");
                    if (hits.size() > 1) throw new IllegalArgumentException("跨数据源重名（" + hits.size() + " 个源），请在工作台按数据源筛选后逐个补录");
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("id", lng(hits.get(0).get("id")));
                    putIfHas(b, "cn_name", cnName);
                    putIfHas(b, "dept", cellStr(row, colIdx.get("所属部门")));
                    putIfHas(b, "app_system", cellStr(row, colIdx.get("应用系统")));
                    putIfHas(b, "admin_owner", cellStr(row, colIdx.get("资源管理员")));
                    putIfHas(b, "admin_contact", cellStr(row, colIdx.get("联系方式")));
                    putIfHas(b, "data_category", cellStr(row, colIdx.get("数据分类")));
                    putIfHas(b, "security_level", sec);
                    putIfHas(b, "layer_code", layer);
                    if (subjectId != null) b.put("subject_id", subjectId);
                    putIfHas(b, "share_type", cellStr(row, colIdx.get("共享类型")));
                    putIfHas(b, "description", cellStr(row, colIdx.get("业务描述")));
                    Map<String, Object> sv = doSave(b);
                    ok++;
                    res.put("status", "ok");
                    res.put("msg", "已保存，填充度 " + lng(sv.get("fill_percent")) + "%");
                } catch (Exception e) {
                    res.put("status", "fail");
                    res.put("msg", rootMsgOf(e));
                }
                results.add(res);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("total", results.size());
        out.put("ok", ok);
        out.put("fail", results.size() - ok);
        out.put("results", results);
        return out;
    }

    private Set<String> codeSet(String sql) {
        Set<String> s = new HashSet<>();
        try { for (Map<String, Object> r : jdbc.queryForList(sql)) s.add(str(r.get("code"))); }
        catch (Exception ignored) {}
        return s;
    }

    private static void putIfHas(Map<String, Object> b, String k, String v) {
        if (v != null && !v.trim().isEmpty()) b.put(k, v.trim());
    }

    private static String cellStr(Row row, int idx) {
        if (idx < 0) return "";
        Cell c = row.getCell(idx);
        if (c == null) return "";
        try {
            if (c.getCellType() == CellType.STRING) return c.getStringCellValue().trim();
            if (c.getCellType() == CellType.NUMERIC) {
                double d = c.getNumericCellValue();
                return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            if (c.getCellType() == CellType.BOOLEAN) return String.valueOf(c.getBooleanCellValue());
            if (c.getCellType() == CellType.FORMULA) return str(c.getCellFormula());
        } catch (Exception ignored) {}
        return "";
    }

    private static String rootMsgOf(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    // ==================== 助手 ====================

    private Map<String, String> toTypeMap(String colsJson) {
        Map<String, String> m = new LinkedHashMap<>();
        try { for (var n : json.readTree(colsJson)) m.put(n.get("name").asText(), n.get("type").asText()); }
        catch (Exception ignored) {}
        return m;
    }

    private static String trimS(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
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
