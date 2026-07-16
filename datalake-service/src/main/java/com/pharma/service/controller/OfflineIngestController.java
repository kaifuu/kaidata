package com.pharma.service.controller;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.DataSourceLoader;
import com.pharma.service.access.ingest.IngestExecutor;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;

/**
 * 离线数据接入 [SYS_ADMIN]：任务 CRUD、源预览、一键执行（全量/增量）、历史、目标预览。
 * <p>执行链路：源数据源 SELECT → ResultSetMetaData 推断列类型 → 自动建 StarRocks 目标表 → 批量写入。
 */
@RestController
@RequestMapping("/api/data-access/offline")
@CrossOrigin(origins = "*")
public class OfflineIngestController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private DataSourceLoader loader;
    @Autowired private IngestExecutor executor;

    // ==================== 任务 CRUD ====================

    @GetMapping("/job/list")
    public List<Map<String, Object>> listJobs() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, source_ds_id, source_table, target_ds_id, target_db, target_table, " +
                "strategy, inc_column, biz_key, last_sync_value, where_clause, status, create_time, update_time " +
                "FROM meta.ing_offline_job ORDER BY id");
    }

    @PostMapping("/job")
    public Map<String, Object> createJob(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        jdbc.update("INSERT INTO meta.ing_offline_job" +
                        "(id, name, source_ds_id, source_table, target_ds_id, target_db, target_table, strategy, inc_column, " +
                        "biz_key, last_sync_value, column_map, where_clause, status, create_by, create_time, update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), lng(b.get("source_ds_id")), str(b.get("source_table")), lng(b.get("target_ds_id")),
                str(b.getOrDefault("target_db", "ods")), str(b.get("target_table")),
                str(b.getOrDefault("strategy", "FULL")), str(b.get("inc_column")),
                str(b.get("biz_key")), str(b.get("last_sync_value")), str(b.get("column_map")),
                str(b.get("where_clause")),
                str(b.getOrDefault("status", "DISABLED")), currentUser(), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/job")
    public Map<String, Object> updateJob(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        String curStatus = jdbc.queryForObject("SELECT status FROM meta.ing_offline_job WHERE id=?", String.class, id);
        if ("ENABLED".equals(curStatus)) throw new AccessDeniedException("任务已上线，请先下线再编辑");
        Timestamp now = new Timestamp(System.currentTimeMillis());
        jdbc.update("UPDATE meta.ing_offline_job SET name=?, source_ds_id=?, source_table=?, target_ds_id=?, target_db=?, " +
                        "target_table=?, strategy=?, inc_column=?, biz_key=?, column_map=?, where_clause=?, status=?, update_time=? WHERE id=?",
                str(b.get("name")), lng(b.get("source_ds_id")), str(b.get("source_table")), lng(b.get("target_ds_id")),
                str(b.getOrDefault("target_db", "ods")), str(b.get("target_table")),
                str(b.getOrDefault("strategy", "FULL")), str(b.get("inc_column")),
                str(b.get("biz_key")), str(b.get("column_map")), str(b.get("where_clause")),
                str(b.getOrDefault("status", "DISABLED")), now, id);
        return Map.of("success", true);
    }

    @DeleteMapping("/job")
    public Map<String, Object> deleteJob(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        String st = jdbc.queryForObject("SELECT status FROM meta.ing_offline_job WHERE id=?", String.class, id);
        if ("ENABLED".equals(st)) throw new AccessDeniedException("任务已上线，请先下线再删除");
        jdbc.update("DELETE FROM meta.ing_offline_run WHERE job_id=?", id);
        jdbc.update("DELETE FROM meta.ing_offline_job WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 复制任务：参数全部一致，名称在原名后递增数字（task→task1→task2），状态置下线。 */
    @PostMapping("/job/copy")
    public Map<String, Object> copyJob(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> src = jdbc.queryForMap(
                "SELECT name, source_ds_id, source_table, target_ds_id, target_db, target_table, strategy, inc_column, " +
                        "biz_key, column_map, where_clause FROM meta.ing_offline_job WHERE id=?", id);
        long newId = System.currentTimeMillis();
        String newName = nextCopyName(str(src.get("name")));
        Timestamp now = new Timestamp(newId);
        jdbc.update("INSERT INTO meta.ing_offline_job" +
                        "(id, name, source_ds_id, source_table, target_ds_id, target_db, target_table, strategy, inc_column, " +
                        "biz_key, last_sync_value, column_map, where_clause, status, create_by, create_time, update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                newId, newName, ((Number) src.get("source_ds_id")).longValue(), str(src.get("source_table")), lng(src.get("target_ds_id")),
                str(src.get("target_db")), str(src.get("target_table")), str(src.get("strategy")),
                str(src.get("inc_column")), str(src.get("biz_key")), "", str(src.get("column_map")),
                str(src.get("where_clause")), "DISABLED", currentUser(), now, now);
        return Map.of("success", true, "id", newId, "name", newName);
    }

    /** 剥离尾部数字得 root，查 root% 的最大数字后缀，返回 root+(max+1)。 */
    private String nextCopyName(String base) {
        String root = base.replaceAll("[0-9]+$", "");
        if (root.isEmpty()) root = base;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM meta.ing_offline_job WHERE name LIKE '" + root.replace("'", "''") + "%'");
        int max = 0;
        for (Map<String, Object> r : rows) {
            String n = str(r.get("name"));
            if (n.startsWith(root) && n.length() > root.length()) {
                try { max = Math.max(max, Integer.parseInt(n.substring(root.length()))); } catch (Exception ignored) {}
            }
        }
        return root + (max + 1);
    }

    /** 上线（ENABLED=锁定，不可编删）。 */
    @PostMapping("/job/online")
    public Map<String, Object> online(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.ing_offline_job SET status='ENABLED', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), id);
        return Map.of("success", true);
    }

    /** 下线（DISABLED=可编删）。 */
    @PostMapping("/job/offline")
    public Map<String, Object> offline(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.ing_offline_job SET status='DISABLED', update_time=? WHERE id=?", new Timestamp(System.currentTimeMillis()), id);
        return Map.of("success", true);
    }

    // ==================== 源预览 ====================

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long dsId = lng(b.get("source_ds_id"));
        String table = str(b.get("source_table"));
        validateTable(table);
        int limit = (int) Math.min(lng(b.get("limit"), 50), 200);
        DataSourceDescriptor ds = loader.load(dsId);
        DataSource pool = registry.getPool(ds);
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " LIMIT " + limit;
        try (var c = pool.getConnection(); var ps = c.createStatement(); var rs = ps.executeQuery(sql)) {
            var md = rs.getMetaData();
            int n = md.getColumnCount();
            List<String> cols = new ArrayList<>();
            for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) row.put(cols.get(i - 1), rs.getObject(i));
                rows.add(row);
            }
            return Map.of("columns", cols, "rows", rows);
        } catch (Exception e) {
            throw new RuntimeException("源预览失败：" + e.getMessage(), e);
        }
    }

    // ==================== 执行 ====================

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam long jobId) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> job = jdbc.queryForMap(
                "SELECT id, name, source_ds_id, source_table, target_ds_id, target_db, target_table, strategy, " +
                        "inc_column, biz_key, last_sync_value, where_clause FROM meta.ing_offline_job WHERE id=?", jobId);
        long runId = System.currentTimeMillis();
        Timestamp start = new Timestamp(runId);
        String strategy = str(job.get("strategy"));
        boolean incremental = "INCREMENTAL".equals(strategy);
        String sourceTable = str(job.get("source_table"));
        String incCol = str(job.get("inc_column"));
        String bizKey = str(job.get("biz_key"));
        String targetDb = str(job.get("target_db"));
        String targetTable = str(job.get("target_table"));
        validateTable(sourceTable);

        DataSourceDescriptor ds = loader.load(((Number) job.get("source_ds_id")).longValue());
        DataSource pool = registry.getPool(ds);

        String sourceSql = "SELECT * FROM " + sourceTable;
        List<String> conds = new ArrayList<>();
        if (incremental && !incCol.isEmpty()) {
            String last = str(job.get("last_sync_value")).replace("'", "''");
            conds.add(incCol + " > '" + last + "'");
        }
        String whereClause = str(job.get("where_clause"));
        if (!whereClause.isEmpty()) conds.add("(" + whereClause + ")");
        if (!conds.isEmpty()) sourceSql += " WHERE " + String.join(" AND ", conds);

        try {
            // 目标数据源：target_ds_id 为空/0 → 主库（兼容存量）；否则按所选目标数据源写入
            Long targetDsId = job.get("target_ds_id") == null ? null : ((Number) job.get("target_ds_id")).longValue();
            boolean useMain = targetDsId == null || targetDsId.longValue() == 0;
            String targetType = "starrocks";
            DataSource tgtPool = null;
            if (!useMain) {
                DataSourceDescriptor tgtDs = loader.load(targetDsId);
                targetType = tgtDs.type;
                tgtPool = registry.getPool(tgtDs);
            }
            // 增量仅支持 StarRocks/Doris（依赖主键模型去重）；由本 catch 落 FAIL run
            if (incremental && !"starrocks".equals(targetType) && !"doris".equals(targetType)) {
                throw new IllegalArgumentException("增量模式仅支持 StarRocks/Doris 目标（依赖主键模型去重）");
            }

            IngestExecutor.Result r = executor.execute(pool, sourceSql, targetDb, targetTable, incremental, bizKey, tgtPool, targetType);
            // 增量更新水位（用目标连接池；增量已限 starrocks/doris，反引号有效）
            if (incremental && !incCol.isEmpty()) {
                try {
                    JdbcTemplate tgtJt = useMain ? this.jdbc : new JdbcTemplate(tgtPool);
                    Object max = tgtJt.queryForObject(
                            "SELECT MAX(" + incCol + ") FROM `" + targetDb + "`.`" + targetTable + "`", Object.class);
                    if (max != null) jdbc.update("UPDATE meta.ing_offline_job SET last_sync_value=? WHERE id=?",
                            String.valueOf(max), jobId);
                } catch (Exception ignored) {}
            }
            jdbc.update("INSERT INTO meta.ing_offline_run(id, job_id, start_time, end_time, status, rows_read, " +
                            "rows_written, error_msg, triggered_by) VALUES (?,?,?,?,?,?,?,?,?)",
                    runId, jobId, start, new Timestamp(System.currentTimeMillis()), "SUCCESS",
                    r.rowsRead, r.rowsWritten, "", currentUser());
            return Map.of("success", true, "runId", runId, "rowsRead", r.rowsRead, "rowsWritten", r.rowsWritten);
        } catch (Exception e) {
            String msg = rootMsg(e);
            jdbc.update("INSERT INTO meta.ing_offline_run(id, job_id, start_time, end_time, status, rows_read, " +
                            "rows_written, error_msg, triggered_by) VALUES (?,?,?,?,?,?,?,?,?)",
                    runId, jobId, start, new Timestamp(System.currentTimeMillis()), "FAIL", 0, 0, msg, currentUser());
            return Map.of("success", false, "runId", runId, "msg", msg);
        }
    }

    @GetMapping("/run/list")
    public List<Map<String, Object>> runList(@RequestParam long jobId, @RequestParam(defaultValue = "50") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, job_id, start_time, end_time, status, rows_read, rows_written, " +
                "error_msg, triggered_by FROM meta.ing_offline_run WHERE job_id=? ORDER BY id DESC LIMIT " + Math.min(limit, 500), jobId);
    }

    /** 清除日志（清洗规则：all/failed/before7d）。jobId 必传，防误清全表。 */
    @DeleteMapping("/run")
    public Map<String, Object> clearRuns(@RequestParam long jobId, @RequestParam(defaultValue = "all") String rule) {
        Authz.require(Authz.SYS_ADMIN);
        StringBuilder sql = new StringBuilder("DELETE FROM meta.ing_offline_run WHERE job_id=?");
        List<Object> args = new ArrayList<>();
        args.add(jobId);
        if ("failed".equalsIgnoreCase(rule)) {
            sql.append(" AND status<>'SUCCESS'");
        } else if ("before7d".equalsIgnoreCase(rule)) {
            sql.append(" AND start_time < DATE_SUB(NOW(), INTERVAL 7 DAY)");
        }
        int n = jdbc.update(sql.toString(), args.toArray());
        return Map.of("success", true, "deleted", n);
    }

    @GetMapping("/target/preview")
    public Map<String, Object> targetPreview(@RequestParam String targetDb, @RequestParam String targetTable,
                                             @RequestParam(required = false) Long targetDsId,
                                             @RequestParam(defaultValue = "50") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        StarRocksDdlIdent(targetDb, targetTable);
        // targetDsId 为空 → 主库（反引号）；否则按目标数据源类型的引用方式查
        JdbcTemplate tgtJt = this.jdbc;
        String qualify = "`" + targetDb + "`.`" + targetTable + "`";
        if (targetDsId != null && targetDsId != 0) {
            DataSourceDescriptor tgtDs = loader.load(targetDsId);
            tgtJt = new JdbcTemplate(registry.getPool(tgtDs));
            qualify = com.pharma.service.access.util.TargetDialect.forType(tgtDs.type).qualify(targetDb, targetTable);
        }
        List<Map<String, Object>> rows = tgtJt.queryForList(
                "SELECT * FROM " + qualify + " LIMIT " + Math.min(limit, 500));
        return Map.of("rows", rows);
    }

    // ==================== 助手 ====================

    private static void validateTable(String t) {
        if (t == null || !t.matches("[a-zA-Z0-9_.]+")) throw new IllegalArgumentException("非法表名: " + t);
    }
    private static void StarRocksDdlIdent(String... ss) {
        for (String s : ss) com.pharma.service.access.util.StarRocksDdlBuilder.ident(s);
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { return lng(o, 0); }
    private static long lng(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }
    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur.getCause() != null && cur.getCause() != cur; i++) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : (cur.getClass().getSimpleName() + ": " + m);
    }
}
