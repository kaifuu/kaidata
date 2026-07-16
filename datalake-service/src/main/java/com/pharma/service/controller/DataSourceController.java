package com.pharma.service.controller;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.ElasticsearchAdapter;
import com.pharma.service.access.util.CryptoUtil;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据源管理 [SYS_ADMIN]：24 类数据源 CRUD、连通性测试、列源表/字段、使用限制。
 * 密码 AES-GCM 加密存 meta.ing_datasource.password；回连时解密注入连接池。
 * <p>
 * 使用限制：数据源被数仓层级 / 接入任务 / 元数据 / 资产等引用后，禁止删除、禁止改 连接类型/地址/端口
 * （可改名称/状态/密码/扩展参数）；usagesOf 查 10 张引用表判定。
 */
@RestController
@RequestMapping("/api/data-access/source")
@CrossOrigin(origins = "*")
public class DataSourceController {

    /** 内部数据源：可充当数仓存储（对应手册的"内部"属性）。 */
    private static final Set<String> INTERNAL_TYPES = Set.of("mysql", "starrocks", "doris", "clickhouse", "hive");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private CryptoUtil crypto;

    // ==================== 类型登记 ====================

    /** 数据源类型登记：[{code, driverAvailable, internal, jarHint?}]。 */
    @GetMapping("/types")
    public List<Map<String, Object>> types() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataSourceAdapter a : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.type());
            m.put("driverAvailable", a.driverAvailable());
            m.put("internal", INTERNAL_TYPES.contains(a.type()));
            if (a.jarHint() != null && !a.jarHint().isEmpty()) m.put("jarHint", a.jarHint());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("code"))));
        return out;
    }

    // ==================== CRUD ====================

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, type, host, port, db_name, username, props, status, tenant_id, create_time, update_time " +
                        "FROM meta.ing_datasource ORDER BY id");
        for (Map<String, Object> r : rows) r.put("internal", INTERNAL_TYPES.contains(String.valueOf(r.get("type"))));
        return rows;
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, type, host, port, db_name, username, password, props, status, tenant_id " +
                        "FROM meta.ing_datasource WHERE id=?", id);
        row.put("password", "***"); // 不回显
        row.put("internal", INTERNAL_TYPES.contains(String.valueOf(row.get("type"))));
        return row;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        Timestamp now = new Timestamp(id);
        jdbc.update("INSERT INTO meta.ing_datasource" +
                        "(id, name, type, host, port, db_name, username, password, props, status, tenant_id, create_by, create_time, update_time) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("name")), str(b.get("type")), str(b.get("host")), (int) lng(b.get("port"), 0),
                str(b.get("db_name")), str(b.get("username")), crypto.encrypt(str(b.get("password"))),
                str(b.get("props")), str(b.getOrDefault("status", "NORMAL")), lng(b.get("tenant_id"), 0),
                currentUser(), now, now);
        return Map.of("success", true, "id", id);
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = lng(b.get("id"), 0);
        // 使用限制：被引用时禁止改 连接类型/地址/端口
        List<String> used = usagesOf(id);
        if (!used.isEmpty()) {
            Map<String, Object> cur = jdbc.queryForMap("SELECT type, host, port FROM meta.ing_datasource WHERE id=?", id);
            boolean connChanged = !String.valueOf(cur.get("type")).equals(str(b.get("type")))
                    || !String.valueOf(cur.get("host")).equals(str(b.get("host")))
                    || ((Number) cur.get("port")).intValue() != (int) lng(b.get("port"), 0);
            if (connChanged) {
                throw new AccessDeniedException("数据源已被使用（" + String.join("、", used) + "），不能修改 连接类型/地址/端口");
            }
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Object pwd = b.get("password");
        if (pwd != null && !String.valueOf(pwd).isEmpty() && !"***".equals(pwd)) {
            jdbc.update("UPDATE meta.ing_datasource SET name=?, type=?, host=?, port=?, db_name=?, username=?, " +
                            "password=?, props=?, status=?, tenant_id=?, update_time=? WHERE id=?",
                    str(b.get("name")), str(b.get("type")), str(b.get("host")), (int) lng(b.get("port"), 0),
                    str(b.get("db_name")), str(b.get("username")), crypto.encrypt(String.valueOf(pwd)),
                    str(b.get("props")), str(b.getOrDefault("status", "NORMAL")), lng(b.get("tenant_id"), 0), now, id);
        } else {
            jdbc.update("UPDATE meta.ing_datasource SET name=?, type=?, host=?, port=?, db_name=?, username=?, " +
                            "props=?, status=?, tenant_id=?, update_time=? WHERE id=?",
                    str(b.get("name")), str(b.get("type")), str(b.get("host")), (int) lng(b.get("port"), 0),
                    str(b.get("db_name")), str(b.get("username")), str(b.get("props")),
                    str(b.getOrDefault("status", "NORMAL")), lng(b.get("tenant_id"), 0), now, id);
        }
        registry.evict(id);
        return Map.of("success", true);
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<String> used = usagesOf(id);
        if (!used.isEmpty()) {
            throw new AccessDeniedException("数据源已被使用（" + String.join("、", used) + "），不能删除；请先解除相关引用");
        }
        registry.evict(id);
        jdbc.update("DELETE FROM meta.ing_datasource WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 使用情况（使用限制） ====================

    /** 返回数据源被引用的模块名列表。inUse=false 表示未被使用，可自由删改。 */
    @GetMapping("/usages")
    public Map<String, Object> usages(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        List<String> modules = usagesOf(id);
        return Map.of("inUse", !modules.isEmpty(), "modules", modules);
    }

    // ==================== 连通测试 / 元数据 ====================

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds;
        if (b.get("id") != null) {
            ds = loadDs(lng(b.get("id"), 0));
        } else {
            ds = DataSourceDescriptor.from(b);
        }
        DataSourceAdapter a = registry.adapter(ds.type);
        if (a == null) return Map.of("ok", false, "msg", "不支持的数据源类型：" + ds.type);
        return a.testConnection(ds);
    }

    @GetMapping("/tables")
    public Object tables(@RequestParam long id, @RequestParam(required = false) String schema) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds = loadDs(id);
        DataSourceAdapter a = registry.adapter(ds.type);
        if (a instanceof ElasticsearchAdapter) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", "elasticsearch");
            r.put("indices", ((ElasticsearchAdapter) a).listIndices(ds));
            return r;
        }
        // schema 为空时按数据源配置的 db_name 过滤：避免整个实例所有库表涌入（配了 ods 就只看 ods 库）
        String effectiveSchema = (schema != null && !schema.isEmpty()) ? schema : ds.dbName;
        return a.listTables(registry.getPool(ds), effectiveSchema);
    }

    @GetMapping("/columns")
    public List<Map<String, Object>> columns(@RequestParam long id,
                                             @RequestParam(required = false) String schema,
                                             @RequestParam String table) {
        Authz.require(Authz.SYS_ADMIN);
        DataSourceDescriptor ds = loadDs(id);
        DataSourceAdapter a = registry.adapter(ds.type);
        return a.describeTable(registry.getPool(ds), schema, table);
    }

    // ==================== 助手 ====================

    /** 查 10 张引用表，返回数据源被引用的模块名列表（空=未被使用）。 */
    private List<String> usagesOf(long id) {
        List<String> modules = new ArrayList<>();
        if (cnt("SELECT COUNT(*) FROM meta.gov_layer_datasource WHERE datasource_id=?", id) > 0) modules.add("数仓层级");
        if (cnt("SELECT COUNT(*) FROM meta.ing_offline_job WHERE source_ds_id=? OR target_ds_id=?", id, id) > 0) modules.add("离线接入");
        if (cnt("SELECT COUNT(*) FROM meta.ing_stream_job WHERE source_ds_id=?", id) > 0) modules.add("实时接入");
        if (cnt("SELECT COUNT(*) FROM meta.ing_profile_job WHERE source_ds_id=?", id) > 0) modules.add("数据探查");
        if (cnt("SELECT COUNT(*) FROM meta.gov_quality_rule WHERE ds_id=?", id) > 0) modules.add("数据质量");
        if (cnt("SELECT COUNT(*) FROM meta.gov_meta_table WHERE ds_id=?", id) > 0) modules.add("元数据");
        if (cnt("SELECT COUNT(*) FROM meta.dev_script WHERE datasource_id=?", id) > 0) modules.add("数据开发");
        if (cnt("SELECT COUNT(*) FROM meta.dev_export WHERE source_ds_id=?", id) > 0) modules.add("数据接出");
        if (cnt("SELECT COUNT(*) FROM meta.data_service WHERE datasource_id=?", id) > 0) modules.add("数据服务");
        if (cnt("SELECT COUNT(*) FROM meta.asset WHERE source_type='DATASOURCE' AND source_id=?", id) > 0) modules.add("数据资产");
        return modules;
    }

    private long cnt(String sql, long id) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, id);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;   // 表缺失等容错
        }
    }
    /** 双参数计数（如 source_ds_id=? OR target_ds_id=? 绑同一 id）。 */
    private long cnt(String sql, long a, long b) {
        try {
            Long c = jdbc.queryForObject(sql, Long.class, a, b);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }

    private DataSourceDescriptor loadDs(long id) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, type, host, port, db_name, username, password, props " +
                        "FROM meta.ing_datasource WHERE id=?", id);
        DataSourceDescriptor ds = new DataSourceDescriptor();
        ds.id = ((Number) row.get("id")).longValue();
        ds.name = String.valueOf(row.getOrDefault("name", ""));
        ds.type = String.valueOf(row.getOrDefault("type", ""));
        ds.host = String.valueOf(row.getOrDefault("host", ""));
        Object p = row.get("port");
        ds.port = p == null ? 0 : ((Number) p).intValue();
        ds.dbName = String.valueOf(row.getOrDefault("db_name", ""));
        ds.username = String.valueOf(row.getOrDefault("username", ""));
        ds.password = crypto.decrypt(String.valueOf(row.getOrDefault("password", "")));
        ds.props = String.valueOf(row.getOrDefault("props", ""));
        return ds;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }
    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
}
