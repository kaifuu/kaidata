package com.pharma.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pharma.service.access.develop.DevScriptExecutor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据集市（数据门户/消费方门户）：已审核表资产浏览/检索 + 表结构样例 + 购物车。 */
@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "*")
public class PortalController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor scriptExecutor;
    private final ObjectMapper json = new ObjectMapper();

    /** 可用资源：库表(仅审核通过的资产，支持全文/分类/标签检索) + 接口(data_service PUBLISHED)。 */
    @GetMapping("/resources")
    public List<Map<String, Object>> resources(@RequestParam(required = false) String type,
                                               @RequestParam(required = false) String kw,
                                               @RequestParam(required = false) Long catalogId,
                                               @RequestParam(required = false) Long tagId) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        if (type == null || type.isEmpty() || "table".equals(type)) {
            StringBuilder sql = new StringBuilder("SELECT a.id AS asset_id, a.name, a.catalog_id, a.description, a.security_level, " +
                    "m.id AS meta_id, m.ds_id, m.schema_name, m.table_name, m.comment, m.columns_json " +
                    "FROM meta.asset a JOIN meta.gov_meta_table m ON m.id=a.source_id " +
                    "WHERE a.status='通过' AND a.source_type='meta_table'");
            List<Object> args = new ArrayList<>();
            if (catalogId != null) { sql.append(" AND a.catalog_id=?"); args.add(catalogId); }
            if (kw != null && !kw.isEmpty()) { sql.append(" AND (a.name LIKE ? OR m.table_name LIKE ? OR m.comment LIKE ?)"); args.add("%" + kw + "%"); args.add("%" + kw + "%"); args.add("%" + kw + "%"); }
            if (tagId != null) { sql.append(" AND m.table_name IN (SELECT target_table FROM meta.gov_tag_relation WHERE tag_id=?)"); args.add(tagId); }
            sql.append(" ORDER BY a.id DESC");
            out.addAll(jdbc.queryForList(sql.toString(), args.toArray()));
        }
        if (type == null || type.isEmpty() || "service".equals(type)) {
            for (Map<String, Object> s : jdbc.queryForList("SELECT code, name, method, params FROM meta.data_service WHERE status='PUBLISHED' ORDER BY id")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "service"); m.put("ref", str(s.get("code"))); m.put("name", str(s.get("name")));
                m.put("method", s.get("method")); m.put("params", s.get("params")); m.put("path", "/open/" + s.get("code"));
                out.add(m);
            }
        }
        return out;
    }

    /** 资源分类（asset_catalog 扁平列表，前端构建树）。 */
    @GetMapping("/catalog-tree")
    public List<Map<String, Object>> catalogTree() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, code, name, parent_id, node_type, sort FROM meta.asset_catalog ORDER BY sort, id");
    }

    /** 数据标签列表（筛选用）。 */
    @GetMapping("/tags")
    public List<Map<String, Object>> tags() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, name, category, color FROM meta.gov_tag ORDER BY id");
    }

    /** 表结构（解析 columns_json）。 */
    @PostMapping("/table-schema")
    public Map<String, Object> tableSchema(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long assetId = lng(b.get("assetId"));
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT m.ds_id, m.schema_name, m.table_name, m.columns_json FROM meta.asset a JOIN meta.gov_meta_table m ON m.id=a.source_id WHERE a.id=?", assetId);
        } catch (Exception e) { return Map.of("status", "FAIL", "msg", "资产或元数据不存在"); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dsId", row.get("ds_id"));
        out.put("fullTable", (str(row.get("schema_name")).isEmpty() ? "" : str(row.get("schema_name")) + ".") + str(row.get("table_name")));
        out.put("columns", parseColumns(str(row.get("columns_json"))));
        return out;
    }

    // ===== 购物车（按用户持久化） =====
    @GetMapping("/cart")
    public List<Map<String, Object>> cart() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, item_type, item_ref, item_name, add_time FROM meta.portal_cart WHERE username=? ORDER BY id DESC", currentUser());
    }
    @PostMapping("/cart/add")
    public Map<String, Object> addCart(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String ref = str(b.get("item_ref")), name = str(b.get("item_name")), type = str(b.get("item_type"));
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM meta.portal_cart WHERE username=? AND item_type=? AND item_ref=?", Integer.class, currentUser(), type, ref);
        if (c != null && c > 0) return Map.of("success", true, "msg", "已在购物车");
        jdbc.update("INSERT INTO meta.portal_cart(id, username, item_type, item_ref, item_name, add_time) VALUES (?,?,?,?,?,?)",
                System.currentTimeMillis(), currentUser(), type, ref, name, new Timestamp(System.currentTimeMillis()));
        return Map.of("success", true);
    }
    @DeleteMapping("/cart/remove")
    public Map<String, Object> removeCart(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.portal_cart WHERE id=? AND username=?", id, currentUser());
        return Map.of("success", true);
    }
    @PostMapping("/cart/clear")
    public Map<String, Object> clearCart() {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.portal_cart WHERE username=?", currentUser());
        return Map.of("success", true);
    }

    /** 库表样例数据：SELECT * FROM table LIMIT 10。 */
    @PostMapping("/preview-table")
    public Map<String, Object> previewTable(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long dsId = lng(b.get("dsId"));
        String table = str(b.get("table"));
        if (!table.matches("[a-zA-Z0-9_.]+")) return Map.of("status", "FAIL", "msg", "非法表名");
        return scriptExecutor.runAdhoc(dsId, "SELECT * FROM " + table + " LIMIT 10");
    }

    /** 资源概览统计。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serviceCount", cnt("SELECT COUNT(*) FROM meta.data_service WHERE status='PUBLISHED'"));
        out.put("tableCount", cnt("SELECT COUNT(*) FROM meta.asset WHERE status='通过' AND source_type='meta_table'"));
        out.put("datasourceCount", cnt("SELECT COUNT(*) FROM meta.ing_datasource"));
        out.put("cartCount", cnt("SELECT COUNT(*) FROM meta.portal_cart WHERE username='" + currentUser().replace("'", "") + "'"));
        out.put("byDs", safeList(() -> jdbc.queryForList("SELECT ds_id, COUNT(*) c FROM meta.gov_meta_table GROUP BY ds_id ORDER BY c DESC")));
        return out;
    }

    private List<Map<String, Object>> parseColumns(String cj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (cj == null || cj.isEmpty()) return out;
        try {
            Object o = json.readValue(cj, Object.class);
            if (o instanceof List) {
                for (Object x : (List<?>) o) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    if (x instanceof String) { c.put("name", x); c.put("type", ""); c.put("comment", ""); }
                    else if (x instanceof Map) {
                        Map<?, ?> mm = (Map<?, ?>) x;
                        c.put("name", mm.get("name") == null ? "" : String.valueOf(mm.get("name")));
                        c.put("type", mm.get("type") == null ? "" : String.valueOf(mm.get("type")));
                        c.put("comment", mm.get("comment") == null ? "" : String.valueOf(mm.get("comment")));
                    } else continue;
                    out.add(c);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    // -------- 助手 --------
    private long cnt(String sql) { try { Long v = jdbc.queryForObject(sql, Long.class); return v == null ? 0 : v; } catch (Exception e) { return 0; } }
    interface Q { List<Map<String, Object>> run(); }
    private List<Map<String, Object>> safeList(Q q) { try { return q.run(); } catch (Exception e) { return List.of(); } }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static String currentUser() { try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); } catch (Exception e) { return "system"; } }
}
