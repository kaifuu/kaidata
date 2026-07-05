package com.pharma.service.controller;

import com.pharma.service.access.adapter.DataSourceAdapter;
import com.pharma.service.access.adapter.DataSourceAdapterRegistry;
import com.pharma.service.access.adapter.DataSourceDescriptor;
import com.pharma.service.access.adapter.ElasticsearchAdapter;
import com.pharma.service.access.util.CryptoUtil;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据源管理 [SYS_ADMIN]：13 类数据源 CRUD、连通性测试、列源表/字段。
 * 密码 AES-GCM 加密存 meta.ing_datasource.password；回连时解密注入连接池。
 */
@RestController
@RequestMapping("/api/data-access/source")
@CrossOrigin(origins = "*")
public class DataSourceController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSourceAdapterRegistry registry;
    @Autowired private CryptoUtil crypto;

    // ==================== 类型登记 ====================

    /** 13 类数据源登记：[{code, driverAvailable, jarHint?}]。 */
    @GetMapping("/types")
    public List<Map<String, Object>> types() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataSourceAdapter a : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.type());
            m.put("driverAvailable", a.driverAvailable());
            if (a.jarHint() != null) m.put("jarHint", a.jarHint());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("code"))));
        return out;
    }

    // ==================== CRUD ====================

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList(
                "SELECT id, name, type, host, port, db_name, username, props, status, tenant_id, create_time, update_time " +
                        "FROM meta.ing_datasource ORDER BY id");
    }

    @GetMapping("/detail")
    public Map<String, Object> detail(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, type, host, port, db_name, username, password, props, status, tenant_id " +
                        "FROM meta.ing_datasource WHERE id=?", id);
        row.put("password", "***"); // 不回显
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
        Object pwd = b.get("password");
        Timestamp now = new Timestamp(System.currentTimeMillis());
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
        registry.evict(id);
        jdbc.update("DELETE FROM meta.ing_datasource WHERE id=?", id);
        return Map.of("success", true);
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
        return a.listTables(registry.getPool(ds), schema);
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
