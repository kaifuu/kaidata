package com.pharma.service.controller;

import com.pharma.service.access.layer.LayerRouter;
import com.pharma.service.security.AuthContext;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 数据仓库 [SYS_ADMIN]：分层管理 + 层级-数据源绑定 + 主题域。
 * <p>P1 升级：分层画像（每层物理表数/行数/存储，StarRocks information_schema 聚合）+ 命名规范巡检
 * （按层 naming_pattern 正则扫描存量表名）+ 主题域编辑。
 * <p>P2 升级（数仓规划闭环）：命名巡检结果落库（gov_naming_issue 状态机 + gov_naming_run 历史，
 * 支持派单到质量工单中心）；分层容量历史快照（gov_layer_stats_history，画像趋势）；层库一键初始化
 * （LayerRouter 按绑定在目标源建层编码库）；主题域富化模型/资产统计。
 */
@RestController
@RequestMapping("/api/data-gov/wh")
@CrossOrigin(origins = "*")
public class DataWhController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LayerRouter layerRouter;

    // ===== 分层 =====
    @GetMapping("/layer")
    public List<Map<String, Object>> listLayer() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT code, name, sort, status, naming_pattern FROM meta.gov_layer ORDER BY sort");
    }
    @PostMapping("/layer")
    public Map<String, Object> createLayer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_layer(code, name, sort, status, naming_pattern) VALUES (?,?,?,?,?)",
                str(b.get("code")), str(b.get("name")), num(b.get("sort")), str(b.getOrDefault("status", "NORMAL")), str(b.get("naming_pattern")));
        return Map.of("success", true);
    }
    @PutMapping("/layer")
    public Map<String, Object> updateLayer(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        validatePattern(str(b.get("naming_pattern")));
        jdbc.update("UPDATE meta.gov_layer SET name=?, sort=?, status=?, naming_pattern=? WHERE code=?",
                str(b.get("name")), num(b.get("sort")), str(b.getOrDefault("status", "NORMAL")), str(b.get("naming_pattern")), str(b.get("code")));
        return Map.of("success", true);
    }
    @DeleteMapping("/layer")
    public Map<String, Object> deleteLayer(@RequestParam String code) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_layer_datasource WHERE layer_code=?", code);
        jdbc.update("DELETE FROM meta.gov_layer WHERE code=?", code);
        return Map.of("success", true);
    }

    /** 正则合法性校验（坏正则会让巡检整层报错）。 */
    private static void validatePattern(String p) {
        if (p == null || p.isEmpty()) return;
        try { Pattern.compile(p); } catch (Exception e) { throw new IllegalArgumentException("命名规范正则非法: " + e.getMessage()); }
    }

    /**
     * 分层画像：每层 物理表数/行数/存储大小/最近更新（StarRocks information_schema 实测，
     * 失败回退元数据登记）+ 命名合规（checked/violate）+ 绑定数据源数 + 最近采集时间。
     */
    @GetMapping("/layer/stats")
    public List<Map<String, Object>> layerStats() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> layers = jdbc.queryForList("SELECT code, name, sort, naming_pattern FROM meta.gov_layer ORDER BY sort");
        Map<String, Map<String, Object>> info = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT TABLE_SCHEMA, COUNT(*) AS tables_cnt, COALESCE(SUM(TABLE_ROWS), 0) AS rows_cnt, " +
                            "COALESCE(SUM(DATA_LENGTH), 0) AS size_bytes, MAX(UPDATE_TIME) AS last_update " +
                            "FROM information_schema.tables WHERE TABLE_TYPE='BASE TABLE' GROUP BY TABLE_SCHEMA")) {
                Map<String, Object> m = new HashMap<>();
                m.put("tables", ((Number) r.get("tables_cnt")).longValue());
                m.put("rows", ((Number) r.get("rows_cnt")).longValue());
                m.put("size_bytes", ((Number) r.get("size_bytes")).longValue());
                m.put("last_update", r.get("last_update"));
                info.put(str(r.get("TABLE_SCHEMA")).toLowerCase(), m);
            }
        } catch (Exception ignored) {}
        // 元数据登记数 + 最近采集（info 缺层时兜底展示）
        Map<String, Long> metaCnt = new HashMap<>();
        Map<String, Object> lastSync = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT COALESCE(layer_code, schema_name) AS layer, COUNT(*) AS c, MAX(synced_time) AS st " +
                            "FROM meta.gov_meta_table GROUP BY COALESCE(layer_code, schema_name)")) {
                String k = str(r.get("layer")).toLowerCase();
                metaCnt.put(k, ((Number) r.get("c")).longValue());
                lastSync.put(k, r.get("st"));
            }
        } catch (Exception ignored) {}
        // 命名合规统计（每层 checked/violate，与 naming-check 同规则）
        Map<String, long[]> naming = new HashMap<>();
        try {
            List<Map<String, Object>> metaTables = jdbc.queryForList(
                    "SELECT table_name, COALESCE(layer_code, schema_name) AS layer FROM meta.gov_meta_table");
            for (Map<String, Object> l : layers) {
                String pattern = str(l.get("naming_pattern"));
                String code = str(l.get("code"));
                if (pattern.isEmpty()) continue;
                Pattern p;
                try { p = Pattern.compile(pattern); } catch (Exception e) { continue; }
                long checked = 0, violate = 0;
                for (Map<String, Object> t : metaTables) {
                    if (!code.equalsIgnoreCase(str(t.get("layer")))) continue;
                    checked++;
                    if (!p.matcher(str(t.get("table_name"))).find()) violate++;
                }
                naming.put(code.toLowerCase(), new long[]{checked, violate});
            }
        } catch (Exception ignored) {}
        // 每层绑定数据源数 + 是否绑定湖源（湖层不在主库 schemata，库按需建 ns）
        Map<String, Long> dsCnt = new HashMap<>();
        Set<String> icebergLayers = new HashSet<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT b.layer_code, COUNT(*) AS c, MAX(CASE WHEN d.type='iceberg' THEN 1 ELSE 0 END) AS ic " +
                            "FROM meta.gov_layer_datasource b JOIN meta.ing_datasource d ON d.id=b.datasource_id " +
                            "GROUP BY b.layer_code")) {
                String k = str(r.get("layer_code")).toLowerCase();
                dsCnt.put(k, ((Number) r.get("c")).longValue());
                if (((Number) r.get("ic")).intValue() > 0) icebergLayers.add(k);
            }
        } catch (Exception ignored) {}
        // 主库已存在的层编码库（schemata 实测，区分「0 张表」与「库未初始化」）
        Set<String> schemas = new HashSet<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT SCHEMA_NAME FROM information_schema.schemata")) {
                schemas.add(str(r.get("SCHEMA_NAME")).toLowerCase());
            }
        } catch (Exception ignored) {}
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : layers) {
            Map<String, Object> row = new LinkedHashMap<>(l);
            String code = str(l.get("code")).toLowerCase();
            Map<String, Object> st = info.get(code);
            if (st != null) {
                row.put("tables", st.get("tables"));
                row.put("rows", st.get("rows"));
                row.put("size_bytes", st.get("size_bytes"));
                row.put("last_update", st.get("last_update"));
            } else {
                row.put("tables", metaCnt.getOrDefault(code, 0L));
                row.put("rows", 0L);
                row.put("size_bytes", 0L);
                row.put("last_update", null);
            }
            row.put("ds_count", dsCnt.getOrDefault(code, 0L));
            row.put("last_sync", lastSync.get(code));
            long[] nm = naming.get(code);
            row.put("naming_checked", nm == null ? 0L : nm[0]);
            row.put("naming_violate", nm == null ? 0L : nm[1]);
            row.put("source", st != null ? "physical" : "meta");
            row.put("db_exists", schemas.contains(code) || icebergLayers.contains(code));
            out.add(row);
        }
        return out;
    }

    // ===== 分层容量历史快照（趋势） =====

    /**
     * 记当日快照到 gov_layer_stats_history（每层 表数/行数/存储，主库 information_schema 实测）。
     * 当日已有快照则跳过（幂等）。返回本次记录的层数。供 WhScheduler 每日定时与启动补记复用。
     */
    public int snapshotLayerStats() {
        LocalDate today = LocalDate.now();
        Integer c;
        try {
            c = jdbc.queryForObject("SELECT COUNT(*) FROM meta.gov_layer_stats_history WHERE snap_date=?",
                    Integer.class, java.sql.Date.valueOf(today));
        } catch (Exception e) {
            return 0;
        }
        if (c != null && c > 0) return 0;
        Map<String, Map<String, Object>> info = physicalLayerInfo();
        int n = 0;
        for (Map<String, Object> l : jdbc.queryForList("SELECT code FROM meta.gov_layer")) {
            String code = str(l.get("code")).toLowerCase();
            Map<String, Object> st = info.getOrDefault(code, Map.of("tables", 0L, "rows", 0L, "size_bytes", 0L));
            jdbc.update("INSERT INTO meta.gov_layer_stats_history(id, snap_date, layer_code, tables_cnt, rows_cnt, size_bytes) " +
                            "VALUES (?,?,?,?,?,?)",
                    System.currentTimeMillis() + n, java.sql.Date.valueOf(today), code,
                    ((Number) st.get("tables")).longValue(), ((Number) st.get("rows")).longValue(),
                    ((Number) st.get("size_bytes")).longValue());
            n++;
        }
        return n;
    }

    /** 主库每层物理聚合（layerStats 与容量快照共用）。 */
    private Map<String, Map<String, Object>> physicalLayerInfo() {
        Map<String, Map<String, Object>> info = new LinkedHashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT TABLE_SCHEMA, COUNT(*) AS tables_cnt, COALESCE(SUM(TABLE_ROWS), 0) AS rows_cnt, " +
                            "COALESCE(SUM(DATA_LENGTH), 0) AS size_bytes FROM information_schema.tables " +
                            "WHERE TABLE_TYPE='BASE TABLE' GROUP BY TABLE_SCHEMA")) {
                Map<String, Object> m = new HashMap<>();
                m.put("tables", ((Number) r.get("tables_cnt")).longValue());
                m.put("rows", ((Number) r.get("rows_cnt")).longValue());
                m.put("size_bytes", ((Number) r.get("size_bytes")).longValue());
                info.put(str(r.get("TABLE_SCHEMA")).toLowerCase(), m);
            }
        } catch (Exception ignored) {}
        return info;
    }

    /** 分层容量趋势（近 N 天，画像页折线）。 */
    @GetMapping("/layer/stats-history")
    public List<Map<String, Object>> layerStatsHistory(@RequestParam(defaultValue = "30") int days) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT snap_date, layer_code, tables_cnt, rows_cnt, size_bytes " +
                "FROM meta.gov_layer_stats_history WHERE snap_date >= DATE_SUB(CURRENT_DATE, INTERVAL " +
                Math.min(Math.max(days, 1), 180) + " DAY) ORDER BY snap_date, layer_code");
    }

    /** 手动补记当日快照（画像页「立即快照」）。 */
    @PostMapping("/layer/stats-snapshot")
    public Map<String, Object> statsSnapshot() {
        Authz.require(Authz.SYS_ADMIN);
        return Map.of("success", true, "layers", snapshotLayerStats());
    }

    /** 层库一键初始化：按层绑定路由在目标源建「层编码库」（无绑定 → 主库 StarRocks）。 */
    @PostMapping("/layer/init-db")
    public Map<String, Object> initLayerDb(@RequestParam String code) {
        Authz.require(Authz.SYS_ADMIN);
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code 必填");
        String target = layerRouter.initLayerDb(code);
        return Map.of("success", true, "target", target);
    }

    /** 层内表清单（画像钻取）：information_schema 实测行数/大小/更新时间 + 元数据注释/采集时间。 */
    @GetMapping("/layer/tables")
    public List<Map<String, Object>> layerTables(@RequestParam String code) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT t.TABLE_NAME AS name, COALESCE(t.TABLE_ROWS, 0) AS rows_cnt, " +
                "COALESCE(t.DATA_LENGTH, 0) AS size_bytes, t.UPDATE_TIME AS last_update, t.CREATE_TIME AS create_time, " +
                "m.comment AS comment, m.synced_time AS synced_time " +
                "FROM information_schema.tables t " +
                "LEFT JOIN meta.gov_meta_table m ON m.schema_name = t.TABLE_SCHEMA AND m.table_name = t.TABLE_NAME " +
                "WHERE t.TABLE_TYPE = 'BASE TABLE' AND LOWER(t.TABLE_SCHEMA) = LOWER(?) " +
                "ORDER BY COALESCE(t.TABLE_ROWS, 0) DESC, t.TABLE_NAME";
        return jdbc.queryForList(sql, code);
    }

    /**
     * 命名规范巡检（闭环版）：扫描集 = 元数据登记表 ∪ 主库物理表直扫（TABLE_SCHEMA=层编码，
     * 未登记元数据的表也纳入）；违规 upsert 落 gov_naming_issue（OPEN→TICKETED→RESOLVED/IGNORED），
     * 本轮未见到的未结违规自动 RESOLVED；执行历史落 gov_naming_run。返回 checked/violate + 存量未结违规。
     * 核心逻辑无鉴权抽离，供 WhScheduler 每日定时复用。
     */
    @GetMapping("/layer/naming-check")
    public Map<String, Object> namingCheck() {
        Authz.require(Authz.SYS_ADMIN);
        return runNamingCheckCore();
    }

    /** 巡检核心（无鉴权，定时任务复用）。 */
    public Map<String, Object> runNamingCheckCore() {
        List<Map<String, Object>> layers = jdbc.queryForList("SELECT code, name, naming_pattern FROM meta.gov_layer ORDER BY sort");
        // ① 扫描集：元数据登记（层归属 COALESCE(layer_code, schema_name)）+ 主库物理表直扫（层编码库）
        Map<String, Map<String, Object>> scan = new LinkedHashMap<>();   // key = layer|table（小写）
        try {
            for (Map<String, Object> t : jdbc.queryForList(
                    "SELECT table_name, COALESCE(layer_code, schema_name) AS layer FROM meta.gov_meta_table")) {
                String layer = str(t.get("layer"));
                if (layer.isEmpty()) continue;
                scan.put(layer.toLowerCase() + "|" + str(t.get("table_name")).toLowerCase(),
                        scanRow(layer, str(t.get("table_name")), "meta"));
            }
        } catch (Exception ignored) {}
        Set<String> layerCodes = new HashSet<>();
        for (Map<String, Object> l : layers) layerCodes.add(str(l.get("code")).toLowerCase());
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.tables WHERE TABLE_TYPE='BASE TABLE'")) {
                String schema = str(r.get("TABLE_SCHEMA")).toLowerCase();
                if (!layerCodes.contains(schema)) continue;
                scan.putIfAbsent(schema + "|" + str(r.get("TABLE_NAME")).toLowerCase(),
                        scanRow(schema, str(r.get("TABLE_NAME")), "physical"));
            }
        } catch (Exception ignored) {}

        // ② 逐层正则校验；③ 违规 upsert 落库
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();   // 未结/忽略的既有违规
        try {
            for (Map<String, Object> e : jdbc.queryForList(
                    "SELECT id, layer_code, table_name, pattern, suggest, status, ticket_id FROM meta.gov_naming_issue " +
                            "WHERE status IN ('OPEN','TICKETED','IGNORED')")) {
                existing.put(str(e.get("layer_code")).toLowerCase() + "|" + str(e.get("table_name")).toLowerCase(), e);
            }
        } catch (Exception ignored) {}
        int total = 0, violate = 0;
        Set<String> seenNow = new HashSet<>();
        for (Map<String, Object> l : layers) {
            String pattern = str(l.get("naming_pattern"));
            String code = str(l.get("code"));
            if (pattern.isEmpty()) continue;
            Pattern p;
            try { p = Pattern.compile(pattern); } catch (Exception e) { continue; }
            for (Map<String, Object> t : scan.values()) {
                if (!code.equalsIgnoreCase(str(t.get("layer")))) continue;
                String name = str(t.get("table"));
                String key = code.toLowerCase() + "|" + name.toLowerCase();
                Map<String, Object> old = existing.get(key);
                if (old != null && "IGNORED".equals(str(old.get("status")))) { total++; continue; }   // 已忽略不再计违规
                total++;
                if (p.matcher(name).find()) continue;
                violate++;
                seenNow.add(key);
                if (old != null) {
                    jdbc.update("UPDATE meta.gov_naming_issue SET last_seen=?, pattern=? WHERE id=?", now, pattern, lng(old.get("id")));
                } else {
                    jdbc.update("INSERT INTO meta.gov_naming_issue(id, layer_code, table_name, pattern, suggest, status, " +
                                    "ticket_id, first_found, last_seen) VALUES (?,?,?,?,?,'OPEN',0,?,?)",
                            System.currentTimeMillis() + (long) (Math.random() * 1000), code, name, pattern,
                            LayerRouter.suggest(code, name), now, now);
                }
            }
        }
        // ④ 本轮未见到（改名/删除）的未结违规 → 自动 RESOLVED
        for (Map.Entry<String, Map<String, Object>> e : existing.entrySet()) {
            if (seenNow.contains(e.getKey())) continue;
            if ("IGNORED".equals(str(e.getValue().get("status")))) continue;
            jdbc.update("UPDATE meta.gov_naming_issue SET status='RESOLVED', resolve_time=? WHERE id=?", now, lng(e.getValue().get("id")));
        }
        // ⑤ 执行历史
        jdbc.update("INSERT INTO meta.gov_naming_run(id, run_time, checked, violate) VALUES (?,?,?,?)",
                System.currentTimeMillis(), now, total, violate);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", total);
        out.put("violate", violate);
        out.put("pass", total - violate);
        // 存量未结违规（含 issue_id/status/ticket_id，供派单/整改操作）
        List<Map<String, Object>> violations = new ArrayList<>();
        try {
            for (Map<String, Object> v : jdbc.queryForList(
                    "SELECT id, layer_code, table_name, pattern, suggest, status, ticket_id, first_found, last_seen " +
                            "FROM meta.gov_naming_issue WHERE status IN ('OPEN','TICKETED') ORDER BY layer_code, table_name")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("issue_id", v.get("id"));
                row.put("layer", str(v.get("layer_code")));
                row.put("table", str(v.get("table_name")));
                row.put("pattern", str(v.get("pattern")));
                row.put("suggest", str(v.get("suggest")));
                row.put("status", str(v.get("status")));
                row.put("ticket_id", v.get("ticket_id"));
                row.put("first_found", v.get("first_found"));
                row.put("last_seen", v.get("last_seen"));
                violations.add(row);
            }
        } catch (Exception ignored) {}
        out.put("violations", violations);
        return out;
    }

    private static Map<String, Object> scanRow(String layer, String table, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", layer);
        m.put("table", table);
        m.put("source", source);
        return m;
    }

    /** 巡检执行历史（趋势曲线：checked/violate 随时间）。 */
    @GetMapping("/layer/naming-runs")
    public List<Map<String, Object>> namingRuns(@RequestParam(defaultValue = "30") int limit) {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, run_time, checked, violate FROM meta.gov_naming_run " +
                "ORDER BY id DESC LIMIT " + Math.min(Math.max(limit, 1), 200));
    }

    /** 违规派单：复用质量工单中心（gov_quality_issue 完整生命周期 + SLA + 流转日志），dimension=命名规范。 */
    @PostMapping("/layer/naming-issue/assign")
    public Map<String, Object> assignNamingIssue(@RequestParam long id, @RequestParam String assignee,
                                                 @RequestParam(required = false) String deadline,
                                                 @RequestParam(defaultValue = "MAJOR") String severity) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> issue = jdbc.queryForMap(
                "SELECT id, layer_code, table_name, pattern, suggest, status FROM meta.gov_naming_issue WHERE id=?", id);
        String st = str(issue.get("status"));
        if (!st.equals("OPEN") && !st.equals("TICKETED")) throw new IllegalArgumentException("该违规已处理：" + st);
        String table = str(issue.get("table_name"));
        String layer = str(issue.get("layer_code"));
        String suggest = str(issue.get("suggest"));
        Timestamp now = new Timestamp(System.currentTimeMillis());
        long ticketId = System.currentTimeMillis();
        // 工单样例 JSON 存建议名与整改 SQL（工单中心「样例数据」导出可见）
        String sampleJson = "{\"suggest\":\"" + suggest + "\",\"sql\":\"ALTER TABLE `" + layer + "`.`" + table
                + "` RENAME TO `" + suggest + "`;\"}";
        jdbc.update("INSERT INTO meta.gov_quality_issue(id, task_id, rule_id, report_id, table_name, dimension, severity, " +
                        "status, assignee, sample_json, violate_count, deadline, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ticketId, 0L, 0L, 0L, table, "命名规范", severity, "ASSIGNED", assignee, sampleJson, 1,
                parseTs(deadline), now);
        try {
            Long maxLog = jdbc.queryForObject("SELECT MAX(id) FROM meta.gov_quality_issue_log", Long.class);
            jdbc.update("INSERT INTO meta.gov_quality_issue_log(id, issue_id, action, operator, comment, create_time) VALUES (?,?,?,?,?,?)",
                    (maxLog == null ? 0 : maxLog) + 1, ticketId, "CREATE", operator(),
                    "命名巡检派单：" + layer + "." + table + " → 建议 " + suggest, now);
        } catch (Exception ignored) {}
        jdbc.update("UPDATE meta.gov_naming_issue SET status='TICKETED', ticket_id=? WHERE id=?", ticketId, id);
        return Map.of("success", true, "ticketId", ticketId);
    }

    /** 违规状态操作：RESOLVED=手动标记已整改；IGNORED=忽略（后续巡检不再计入违规）。 */
    @PostMapping("/layer/naming-issue/status")
    public Map<String, Object> namingIssueStatus(@RequestParam long id, @RequestParam String status) {
        Authz.require(Authz.SYS_ADMIN);
        if (!status.equals("RESOLVED") && !status.equals("IGNORED") && !status.equals("OPEN"))
            throw new IllegalArgumentException("status 仅支持 RESOLVED/IGNORED/OPEN");
        jdbc.update("UPDATE meta.gov_naming_issue SET status=?, resolve_time=? WHERE id=?",
                status, status.equals("OPEN") ? null : new Timestamp(System.currentTimeMillis()), id);
        return Map.of("success", true);
    }

    /** "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-ddTHH:mm" → Timestamp；空/非法返回 null。 */
    private static Timestamp parseTs(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Timestamp.valueOf(s.replace('T', ' ').length() == 16 ? s.replace('T', ' ') + ":00" : s.replace('T', ' ')); }
        catch (Exception e) { return null; }
    }

    private static String operator() {
        try { return String.valueOf(AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }

    // ===== 层级-数据源绑定 =====

    /** 绑定列表（JOIN 数据源富化：名称/类型/地址/库/状态）。layerCode 可选 —— 不传返回全部层（左树统计 + 「全部层级」视图）。 */
    @GetMapping("/layer/datasource")
    public List<Map<String, Object>> listLayerDs(@RequestParam(required = false) String layerCode) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT b.id, b.layer_code, b.datasource_id, d.name AS ds_name, d.type AS ds_type, " +
                "d.host, d.port, d.db_name, d.status AS ds_status " +
                "FROM meta.gov_layer_datasource b LEFT JOIN meta.ing_datasource d ON b.datasource_id = d.id";
        if (layerCode == null || layerCode.isEmpty()) {
            return jdbc.queryForList(sql + " ORDER BY b.layer_code, b.id");
        }
        return jdbc.queryForList(sql + " WHERE b.layer_code=? ORDER BY b.id", layerCode);
    }

    /** 绑定数据源（支持批量：datasource_ids 数组；兼容单数 datasource_id）。已绑定的跳过。 */
    @PostMapping("/layer/datasource")
    public Map<String, Object> bindLayerDs(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String lc = str(b.get("layer_code"));
        if (lc.isEmpty()) throw new IllegalArgumentException("layer_code 必填");
        List<Long> dsIds = new ArrayList<>();
        if (b.get("datasource_ids") instanceof List<?> arr) {
            for (Object o : arr) dsIds.add(lng(o));
        } else if (lng(b.get("datasource_id")) > 0) {
            dsIds.add(lng(b.get("datasource_id")));
        }
        int added = 0;
        for (long dsId : dsIds) {
            if (dsId <= 0) continue;
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM meta.gov_layer_datasource WHERE layer_code=? AND datasource_id=?",
                    Integer.class, lc, dsId);
            if (c != null && c > 0) continue;
            jdbc.update("INSERT INTO meta.gov_layer_datasource(id, layer_code, datasource_id) VALUES (?,?,?)",
                    System.currentTimeMillis() + added, lc, dsId);
            added++;
        }
        return Map.of("success", true, "added", added);
    }
    @DeleteMapping("/layer/datasource")
    public Map<String, Object> unbindLayerDs(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.gov_layer_datasource WHERE id=?", id);
        return Map.of("success", true);
    }

    // ===== 主题域（树，富化：模型数/资产数/子域数 —— 主题域画像） =====
    @GetMapping("/subject")
    public List<Map<String, Object>> listSubject() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> flat = jdbc.queryForList("SELECT id, code, name, parent_id, sort FROM meta.gov_subject ORDER BY sort, id");
        // 域编码 → 模型数（模型 domain 存编码或文本，含存量兼容）
        Map<String, Long> modelCnt = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT domain, COUNT(*) AS c FROM meta.gov_model WHERE domain IS NOT NULL AND domain<>'' GROUP BY domain")) {
                modelCnt.put(str(r.get("domain")).toLowerCase(), ((Number) r.get("c")).longValue());
            }
        } catch (Exception ignored) {}
        // 域 id → 资产数（asset.subject_id，资产登记「所属主题」）
        Map<Long, Long> assetCnt = new HashMap<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT subject_id, COUNT(*) AS c FROM meta.asset WHERE subject_id>0 GROUP BY subject_id")) {
                assetCnt.put(lng(r.get("subject_id")), ((Number) r.get("c")).longValue());
            }
        } catch (Exception ignored) {}
        Map<Long, List<Map<String, Object>>> byParent = new HashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> s : flat) {
            Object p = s.get("parent_id");
            if (p == null || lng(p) == 0) roots.add(s);
            else byParent.computeIfAbsent(lng(p), k -> new ArrayList<>()).add(s);
        }
        for (Map<String, Object> r : roots) r.put("children", byParent.getOrDefault(lng(r.get("id")), List.of()));
        for (Map<String, Object> s : flat) {
            List<Map<String, Object>> children = byParent.getOrDefault(lng(s.get("id")), List.of());
            long childModels = ((Number) modelCnt.getOrDefault(str(s.get("code")).toLowerCase(), 0L)).longValue();
            long childAssets = assetCnt.getOrDefault(lng(s.get("id")), 0L);
            for (Map<String, Object> c : children) {   // 子域并入父域统计（父域画像=自身+子域）
                childModels += ((Number) modelCnt.getOrDefault(str(c.get("code")).toLowerCase(), 0L)).longValue();
                childAssets += assetCnt.getOrDefault(lng(c.get("id")), 0L);
            }
            s.put("model_count", childModels);
            s.put("asset_count", childAssets);
            s.put("child_count", children.size());
        }
        return roots;
    }
    @PostMapping("/subject")
    public Map<String, Object> createSubject(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("INSERT INTO meta.gov_subject(id, code, name, parent_id, sort) VALUES (?,?,?,?,?)",
                System.currentTimeMillis(), str(b.get("code")), str(b.get("name")), lng(b.get("parent_id")), num(b.get("sort")));
        return Map.of("success", true);
    }
    @PutMapping("/subject")
    public Map<String, Object> updateSubject(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"));
        if (id == 0) throw new IllegalArgumentException("id 必填");
        if (id == lng(b.get("parent_id"))) throw new IllegalArgumentException("父节点不能是自身");
        jdbc.update("UPDATE meta.gov_subject SET code=?, name=?, parent_id=?, sort=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), lng(b.get("parent_id")), num(b.get("sort")), id);
        return Map.of("success", true);
    }
    @DeleteMapping("/subject")
    public Map<String, Object> deleteSubject(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM meta.gov_subject WHERE parent_id=?", Integer.class, id);
        if (c != null && c > 0) throw new IllegalArgumentException("存在子主题域，先删子节点");
        jdbc.update("DELETE FROM meta.gov_subject WHERE id=?", id);
        return Map.of("success", true);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static int num(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).intValue(); try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
}
