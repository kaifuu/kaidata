package com.pharma.service.controller;

import com.pharma.service.access.develop.DevScriptExecutor;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据集市（数据门户/消费方门户）[SYS_ADMIN]：聚合数据服务开放资源 + 库表元数据 + 购物车 + 对接预览。 */
@RestController
@RequestMapping("/api/market")
@CrossOrigin(origins = "*")
public class PortalController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DevScriptExecutor scriptExecutor;

    /** 可用资源：接口(data_service PUBLISHED) + 库表(gov_meta_table)。 */
    @GetMapping("/resources")
    public List<Map<String, Object>> resources(@RequestParam(required = false) String type) {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        if (type == null || type.isEmpty() || "service".equals(type)) {
            for (Map<String, Object> s : jdbc.queryForList("SELECT code, name, method, params FROM meta.data_service WHERE status='PUBLISHED' ORDER BY id")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "service"); m.put("ref", str(s.get("code"))); m.put("name", str(s.get("name")));
                m.put("method", s.get("method")); m.put("params", s.get("params"));
                m.put("path", "/open/" + s.get("code"));
                out.add(m);
            }
        }
        if (type == null || type.isEmpty() || "table".equals(type)) {
            for (Map<String, Object> t : jdbc.queryForList("SELECT id, ds_id, schema_name, table_name, comment FROM meta.gov_meta_table ORDER BY ds_id, table_name")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "table"); m.put("ref", t.get("id")); m.put("ds_id", t.get("ds_id"));
                m.put("schema", t.get("schema_name")); m.put("table", t.get("table_name"));
                m.put("name", str(t.get("table_name"))); m.put("comment", t.get("comment"));
                m.put("full", (str(t.get("schema_name")).isEmpty() ? "" : str(t.get("schema_name")) + ".") + str(t.get("table_name")));
                out.add(m);
            }
        }
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

    /** 库表预览对接：SELECT * FROM table LIMIT 10。 */
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
        out.put("tableCount", cnt("SELECT COUNT(*) FROM meta.gov_meta_table"));
        out.put("datasourceCount", cnt("SELECT COUNT(*) FROM meta.ing_datasource"));
        out.put("cartCount", cnt("SELECT COUNT(*) FROM meta.portal_cart WHERE username='" + currentUser().replace("'", "") + "'"));
        out.put("byDs", safeList(() -> jdbc.queryForList("SELECT ds_id, COUNT(*) c FROM meta.gov_meta_table GROUP BY ds_id ORDER BY c DESC")));
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
