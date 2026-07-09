package com.pharma.service.access.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.profile.VersionDiffer;
import com.pharma.service.access.util.SqlBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;

/**
 * 元数据采集执行核心：对采集任务绑定的数据源 → 遍历表 → describeTable 取结构 →
 * 与 gov_meta_table 当前生效版本 diff → 有变化则新增 gov_meta_version（不自动生效到补录）→ 写采集日志。
 * 供 DataMetaCollectController（手动执行）与 MetaCollectScheduler（周期执行）复用，避免循环依赖。
 * <p>关键规则：新版本只写 gov_meta_version，绝不覆盖 gov_meta_table.columns_json / current_version / 补录列。
 */
@Component
public class MetaCollectExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public Map<String, Object> run(long jobId) {
        long runId = System.currentTimeMillis();
        Timestamp start = new Timestamp(runId);
        String status = "SUCCESS";
        int total = 0, added = 0, changed = 0, removed = 0;
        String errorMsg = "";
        List<Map<String, Object>> detail = new ArrayList<>();
        try {
            Map<String, Object> job = jdbc.queryForMap(
                    "SELECT id, name, ds_id, schema_filter, table_filter FROM meta.gov_meta_collect_job WHERE id=?", jobId);
            long dsId = lng(job.get("ds_id"));
            String schemaFilter = str(job.get("schema_filter"));
            String tableFilter = str(job.get("table_filter"));

            DataSourceDescriptor ds = loader.load(dsId);
            DataSourceAdapter adapter = registry.adapter(ds.type);
            DataSource pool = registry.getPool(ds);
            List<Map<String, Object>> allTables = adapter.listTables(pool, schemaFilter.isEmpty() ? null : schemaFilter);
            // 单次采集表数上限，防止超大库拖垮单节点 StarRocks（大库请用 table_filter 分批）
            final int MAX_TABLES = 200;
            boolean truncated = allTables.size() > MAX_TABLES;
            List<Map<String, Object>> tables = truncated ? new ArrayList<>(allTables.subList(0, MAX_TABLES)) : allTables;

            // 已采集表（用于 removed 判定）
            Set<String> known = new HashSet<>();
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT schema_name, table_name FROM meta.gov_meta_table WHERE ds_id=?", dsId)) {
                known.add(norm(str(r.get("schema_name")), str(r.get("table_name"))));
            }
            Set<String> seen = new HashSet<>();

            for (Map<String, Object> t : tables) {
                String schema = str(t.get("schema_name"));
                String table = str(t.get("name"));
                if (table.isEmpty() || !matchFilter(table, tableFilter)) continue;
                total++;
                seen.add(norm(schema, table));
                try {
                    String[] sp = SqlBuilder.splitTable(schema.isEmpty() ? table : schema + "." + table);
                    List<Map<String, Object>> cols = adapter.describeTable(pool, sp[0], sp[1]);
                    String colsJson = json.writeValueAsString(cols);
                    Timestamp now = new Timestamp(System.currentTimeMillis());

                    List<Map<String, Object>> exist = jdbc.queryForList(
                            "SELECT id, current_version, columns_json FROM meta.gov_meta_table WHERE ds_id=? AND schema_name=? AND table_name=?",
                            dsId, schema, table);
                    if (exist.isEmpty()) {
                        long metaId = System.currentTimeMillis() + (long) (Math.random() * 1000);
                        jdbc.update("INSERT INTO meta.gov_meta_table(id, ds_id, schema_name, table_name, comment, columns_json, " +
                                        "row_count, synced_time, current_version, mount_status, fill_percent) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                                metaId, dsId, schema, table, str(t.get("comment")), colsJson, 0L, now, 1, "NONE", 0);
                        jdbc.update("INSERT INTO meta.gov_meta_version(id, meta_id, version_n, columns_json, change_type, " +
                                        "change_detail, source, created_time) VALUES (?,?,?,?,?,?,?,?)",
                                metaId + 1, metaId, 1, colsJson, "INIT", "", "COLLECT", now);
                        added++;
                        detail.add(detailRow(schema, table, "INIT", 1, false));
                    } else {
                        long metaId = lng(exist.get(0).get("id"));
                        String prevJson = str(exist.get(0).get("columns_json"));
                        VersionDiffer.Diff diff = prevJson.isEmpty() ? new VersionDiffer.Diff()
                                : VersionDiffer.diff(toTypeMap(prevJson), toTypeMap(colsJson));
                        if (diff.hasChange()) {
                            Integer prevMax = maxVersion(metaId);
                            int newVer = (prevMax == null ? 0 : prevMax) + 1;
                            String changeDetail = "+[" + String.join(",", diff.added) + "] -[" + String.join(",", diff.removed) +
                                    "] ~[" + String.join(";", diff.typeChanged) + "]";
                            jdbc.update("INSERT INTO meta.gov_meta_version(id, meta_id, version_n, columns_json, change_type, " +
                                            "change_detail, source, created_time) VALUES (?,?,?,?,?,?,?,?)",
                                    System.currentTimeMillis() + newVer, metaId, newVer, colsJson, "MODIFIED", trim(changeDetail, 2000), "COLLECT", now);
                            changed++;
                            detail.add(detailRow(schema, table, "MODIFIED", newVer, true));
                        }
                        // 结构未变或已记版本：仅刷新 synced_time，绝不覆盖 columns_json / current_version / 补录列
                        jdbc.update("UPDATE meta.gov_meta_table SET synced_time=? WHERE id=?", now, metaId);
                    }
                } catch (Exception ex) {
                    detail.add(detailRow(schema, table, "ERROR:" + rootMsg(ex), 0, false));
                }
            }
            for (String k : known) if (!seen.contains(k)) removed++;
            if (truncated) detail.add(detailRow("", "(本批仅采集 " + MAX_TABLES + "/" + allTables.size() + " 张，请用 table_filter 分批)", "TRUNCATED", 0, false));
        } catch (Exception e) {
            status = "FAIL";
            errorMsg = rootMsg(e);
        }
        Timestamp end = new Timestamp(System.currentTimeMillis());
        String detailJson = trim(safeWrite(detail), 65000);
        jdbc.update("INSERT INTO meta.gov_meta_collect_log(id, job_id, start_time, end_time, status, tables_total, " +
                        "tables_added, tables_changed, tables_removed, detail, error_msg, triggered_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                runId, jobId, start, end, status, total, added, changed, removed, detailJson, errorMsg, currentUser());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("status", status);
        r.put("tablesTotal", total);
        r.put("tablesAdded", added);
        r.put("tablesChanged", changed);
        r.put("tablesRemoved", removed);
        r.put("msg", errorMsg);
        return r;
    }

    // -------- 助手 --------
    private Integer maxVersion(long metaId) {
        try {
            return jdbc.queryForObject("SELECT MAX(version_n) FROM meta.gov_meta_version WHERE meta_id=?", Integer.class, metaId);
        } catch (Exception e) { return null; }
    }

    private Map<String, String> toTypeMap(String colsJson) {
        Map<String, String> m = new LinkedHashMap<>();
        try { for (var n : json.readTree(colsJson)) m.put(n.get("name").asText(), n.get("type").asText()); }
        catch (Exception ignored) {}
        return m;
    }

    private Map<String, Object> detailRow(String schema, String table, String change, int version, boolean pending) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("schema", schema);
        d.put("table", table);
        d.put("change", change);
        d.put("version", version);
        d.put("pending", pending);
        return d;
    }

    private static String norm(String schema, String table) {
        return schema == null || schema.isEmpty() ? table : schema + "." + table;
    }

    /** table_filter 为空=全部；否则逗号分隔的精确表名白名单（忽略大小写）。 */
    private static boolean matchFilter(String table, String filter) {
        if (filter == null || filter.isBlank()) return true;
        for (String p : filter.split(",")) {
            String s = p.trim();
            if (!s.isEmpty() && s.equalsIgnoreCase(table)) return true;
        }
        return false;
    }

    private String safeWrite(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }

    private static String trim(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) : s);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }

    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
}
