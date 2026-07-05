package com.pharma.service.access.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.util.StarRocksDdlBuilder;
import com.pharma.service.access.util.SqlBuilder;
import com.pharma.service.access.util.TypeMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探查执行核心：对任务的每张表 → 取结构快照 → 与上次版本 diff → 字段统计 → 存快照/diff →
 * 首次+目标无表则自动建模 → 结构变化预警。全程拼接 log_text。
 * 供 ProfileController（手动执行）与 ProfileScheduler（周期执行）复用，避免循环依赖。
 */
@Component
public class ProfileExecutor {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceLoader loader;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private AutoModler autoModler;
    private final ObjectMapper json = new ObjectMapper();

    public Map<String, Object> run(long jobId) {
        long runId = System.currentTimeMillis();
        Timestamp start = new Timestamp(runId);
        StringBuilder log = new StringBuilder();
        log.append("探查任务 ").append(jobId).append(" 开始 ").append(start).append("\n");

        String status = "SUCCESS";
        int tablesTotal = 0, tablesChanged = 0;
        String errorMsg = "";
        try {
            Map<String, Object> job = jdbc.queryForMap(
                    "SELECT id, name, source_ds_id, target_db, first_create_table, alert_enabled, extra_columns " +
                            "FROM meta.ing_profile_job WHERE id=?", jobId);
            long dsId = ((Number) job.get("source_ds_id")).longValue();
            String targetDb = str(job.get("target_db"));
            boolean firstCreate = bool(job.get("first_create_table"));
            boolean alert = bool(job.get("alert_enabled"));
            String extra = str(job.get("extra_columns"));

            DataSourceDescriptor ds = loader.load(dsId);
            DataSourceAdapter adapter = registry.adapter(ds.type);
            DataSource pool = registry.getPool(ds);
            log.append("源数据源: ").append(ds.name).append(" (").append(ds.type).append(")\n");

            List<Map<String, Object>> tableCfgs = jdbc.queryForList(
                    "SELECT id, table_name, columns_config FROM meta.ing_profile_table WHERE job_id=?", jobId);
            tablesTotal = tableCfgs.size();
            log.append("待探查表数: ").append(tablesTotal).append("\n");

            for (Map<String, Object> tc : tableCfgs) {
                String tableName = str(tc.get("table_name"));
                String cfgJson = str(tc.get("columns_config"));
                try {
                    tablesChanged += profileOne(jobId, runId, dsId, adapter, pool, tableName, cfgJson,
                            targetDb, firstCreate, alert, extra, log);
                } catch (Exception e) {
                    log.append("  [").append(tableName).append("] 探查失败: ").append(rootMsg(e)).append("\n");
                }
            }
            log.append("完成: 变化表 ").append(tablesChanged).append("/").append(tablesTotal).append("\n");
        } catch (Exception e) {
            status = "FAIL";
            errorMsg = rootMsg(e);
            log.append("任务失败: ").append(errorMsg).append("\n");
        }
        Timestamp end = new Timestamp(System.currentTimeMillis());
        String logText = log.length() > 65000 ? log.substring(0, 65000) : log.toString();
        jdbc.update("INSERT INTO meta.ing_profile_run(id, job_id, start_time, end_time, status, tables_changed, " +
                        "tables_total, error_msg, log_text, triggered_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                runId, jobId, start, end, status, tablesChanged, tablesTotal, errorMsg, logText, "system");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runId", runId);
        r.put("status", status);
        r.put("tablesTotal", tablesTotal);
        r.put("tablesChanged", tablesChanged);
        r.put("msg", errorMsg);
        return r;
    }

    /** 探查单表，返回 1（有变化）/0（无变化）。 */
    @SuppressWarnings("unchecked")
    private int profileOne(long jobId, long runId, long dsId, DataSourceAdapter adapter, DataSource pool,
                           String tableName, String cfgJson, String targetDb, boolean firstCreate,
                           boolean alert, String extra, StringBuilder log) throws Exception {
        // ① 当前结构（tableName 可能含 schema 前缀，拆分后查）
        String[] sp = SqlBuilder.splitTable(tableName);
        List<Map<String, Object>> curCols = adapter.describeTable(pool, sp[0], sp[1]);
        Map<String, String> curMap = new LinkedHashMap<>();
        for (Map<String, Object> c : curCols) curMap.put(str(c.get("name")), str(c.get("type")));

        // ② 上次版本
        Integer prevVer = maxVersion(jobId, tableName);
        String prevJson = prevVer == null ? null : prevColumnsJson(jobId, tableName, prevVer);
        Map<String, String> prevMap = prevJson == null ? Map.of()
                : toTypeMap(json.readTree(prevJson));

        // ③ diff（首次无 prev 不算变化）
        VersionDiffer.Diff diff = prevMap.isEmpty() ? new VersionDiffer.Diff() : VersionDiffer.diff(prevMap, curMap);
        if (diff.hasChange()) {
            jdbc.update("INSERT INTO meta.ing_profile_diff(id, snapshot_id, job_id, table_name, added, removed, " +
                            "type_changed, created_time) VALUES (?,?,?,?,?,?,?,?)",
                    System.currentTimeMillis(), 0, jobId, tableName,
                    String.join(",", diff.added), String.join(",", diff.removed),
                    String.join(";", diff.typeChanged), new Timestamp(System.currentTimeMillis()));
        }

        // ④ 字段统计
        Map<String, List<String>> options = parseOptions(cfgJson);
        Map<String, Object> stats = Profiler.profile(pool, tableName, curCols, options);

        // ⑤ 存快照
        int curVer = (prevVer == null ? 0 : prevVer) + 1;
        String columnsJson = json.writeValueAsString(curCols);
        String statsJson = json.writeValueAsString(stats);
        long snapId = System.currentTimeMillis() + curVer;
        jdbc.update("INSERT INTO meta.ing_profile_snapshot(id, job_id, table_name, version_n, columns_json, " +
                        "stats_json, run_id, created_time) VALUES (?,?,?,?,?,?,?,?)",
                snapId, jobId, tableName, curVer, columnsJson, statsJson, runId, new Timestamp(System.currentTimeMillis()));
        // 同步技术元数据到 gov_meta_table（探查即采集）
        upsertMeta(dsId, sp[0] == null ? "" : sp[0], sp[1], columnsJson);

        // ⑥ 首次探查 + 目标无表 → 自动建模
        if (firstCreate && curVer == 1 && targetDb != null && !targetDb.isEmpty()) {
            List<StarRocksDdlBuilder.ColumnDef> ddlCols = new ArrayList<>();
            for (Map<String, Object> c : curCols) {
                ddlCols.add(new StarRocksDdlBuilder.ColumnDef(str(c.get("name")), TypeMapper.mapByName(str(c.get("type")))));
            }
            for (StarRocksDdlBuilder.ColumnDef ec : parseExtra(extra)) ddlCols.add(ec);
            boolean created = autoModler.createIfAbsent(targetDb, safeName(tableName), ddlCols, null);
            log.append("  [").append(tableName).append("] v").append(curVer)
                    .append(created ? " 自动建模→" + targetDb + "." + safeName(tableName) : " 建表跳过(目标已存在或未配层级)")
                    .append("\n");
        }

        // ⑦ 结构变化预警（写审计 action）
        if (alert && diff.hasChange()) {
            log.append("  [").append(tableName).append("] 结构变化预警: +").append(diff.added.size())
                    .append(" -").append(diff.removed.size()).append(" ~").append(diff.typeChanged.size()).append("\n");
        }

        log.append("  [").append(tableName).append("] v").append(curVer)
                .append(" 字段").append(curCols.size()).append(" 总行").append(stats.get("_total")).append("\n");
        return diff.hasChange() ? 1 : 0;
    }

    // -------- 助手 --------

    /** 探查结果同步到技术元数据表（upsert）。 */
    private void upsertMeta(long dsId, String schema, String table, String colsJson) {
        try {
            List<Map<String, Object>> exist = jdbc.queryForList(
                    "SELECT id FROM meta.gov_meta_table WHERE ds_id=? AND schema_name=? AND table_name=?", dsId, schema, table);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            if (exist.isEmpty()) {
                jdbc.update("INSERT INTO meta.gov_meta_table(id, ds_id, schema_name, table_name, comment, columns_json, row_count, synced_time) VALUES (?,?,?,?,?,?,?,?)",
                        System.currentTimeMillis() + (long) (Math.random() * 1000), dsId, schema, table, "", colsJson, 0L, now);
            } else {
                jdbc.update("UPDATE meta.gov_meta_table SET columns_json=?, synced_time=? WHERE id=?",
                        colsJson, now, ((Number) exist.get(0).get("id")).longValue());
            }
        } catch (Exception ignored) {}
    }

    private Integer maxVersion(long jobId, String table) {
        try {
            Integer v = jdbc.queryForObject(
                    "SELECT MAX(version_n) FROM meta.ing_profile_snapshot WHERE job_id=? AND table_name=?",
                    Integer.class, jobId, table);
            return v;
        } catch (Exception e) { return null; }
    }

    private String prevColumnsJson(long jobId, String table, int ver) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT columns_json FROM meta.ing_profile_snapshot WHERE job_id=? AND table_name=? AND version_n=? " +
                            "ORDER BY id DESC LIMIT 1", jobId, table, ver);
            return rows.isEmpty() ? null : str(rows.get(0).get("columns_json"));
        } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseOptions(String cfgJson) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (cfgJson == null || cfgJson.isBlank()) return out;
        try {
            Map<String, Object> m = json.readValue(cfgJson, Map.class);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof List) out.put(e.getKey(), (List<String>) e.getValue());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private List<StarRocksDdlBuilder.ColumnDef> parseExtra(String extra) {
        List<StarRocksDdlBuilder.ColumnDef> out = new ArrayList<>();
        if (extra == null || extra.isBlank()) return out;
        try {
            for (var n : json.readTree(extra)) {
                String name = n.has("name") ? n.get("name").asText() : n.get("col").asText();
                String type = n.has("type") ? n.get("type").asText() : "VARCHAR(255)";
                out.add(new StarRocksDdlBuilder.ColumnDef(name, type));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private Map<String, String> toTypeMap(com.fasterxml.jackson.databind.JsonNode arr) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            for (var n : arr) m.put(n.get("name").asText(), n.get("type").asText());
        } catch (Exception ignored) {}
        return m;
    }

    /** 表名 → 合法建表名（去 schema 前缀，仅留字母数字下划线）。 */
    private static String safeName(String t) {
        int i = t.lastIndexOf('.');
        String s = i >= 0 ? t.substring(i + 1) : t;
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static boolean bool(Object o) { return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
