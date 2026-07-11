package com.pharma.service.controller;

import com.pharma.service.access.service.OpenGrantService;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据开放授权管理 [SYS_ADMIN]：基于已审核资产创建开放授权（API / 库表）+ appkey + 限流配置。
 */
@RestController
@RequestMapping("/api/data-open")
@CrossOrigin(origins = "*")
public class OpenGrantController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenGrantService grantService;

    /** 授权列表（带资产名/状态 + 累计调用数） */
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT g.id, g.name, g.asset_id, g.open_type, g.app_key, g.app_secret, g.grantee, " +
                "g.fields_json, g.service_code, g.limit_count, g.limit_qps, g.expire_time, g.status, g.create_time, " +
                "a.name AS asset_name, a.status AS asset_status, s.params AS svc_params, " +
                "(SELECT COUNT(*) FROM meta.data_service_log l, meta.data_service s WHERE s.code=g.service_code AND l.service_id=s.id) AS calls " +
                "FROM meta.data_open_grant g LEFT JOIN meta.asset a ON a.id=g.asset_id " +
                "LEFT JOIN meta.data_service s ON s.code=g.service_code ORDER BY g.id DESC");
    }

    /** 可开放的已审核表资产（含字段列表） */
    @GetMapping("/assets")
    public List<Map<String, Object>> assets() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT a.id, a.name, a.source_id, m.schema_name, m.table_name, m.columns_json " +
                "FROM meta.asset a LEFT JOIN meta.gov_meta_table m ON m.id=a.source_id " +
                "WHERE a.status='通过' AND a.source_type='meta_table' ORDER BY a.id DESC");
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        OpenGrantService.GrantInput in = new OpenGrantService.GrantInput();
        in.name = str(b.get("name"));
        in.assetId = lng(b.get("asset_id"));
        in.openType = str(b.getOrDefault("open_type", "API"));
        in.fields = parseStrList(b.get("fields"));
        in.paramField = str(b.get("param_field"));
        in.grantee = str(b.get("grantee"));
        in.limitCount = lng(b.get("limit_count"));
        in.limitQps = (int) lng(b.get("limit_qps"));
        in.expireTime = parseTs(b.get("expire_time"));
        in.createBy = currentUser();
        OpenGrantService.GrantResult r = grantService.create(in);
        return Map.of("success", true, "id", r.id, "app_key", r.appKey, "app_secret", r.appSecret, "service_code", r.serviceCode);
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        grantService.delete(id);
        return Map.of("success", true);
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.data_open_grant SET status='ACTIVE' WHERE id=?", id);
        return Map.of("success", true);
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.data_open_grant SET status='DISABLED' WHERE id=?", id);
        return Map.of("success", true);
    }

    @PostMapping("/regen-key")
    public Map<String, Object> regenKey(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        String k = java.util.UUID.randomUUID().toString().replace("-", "");
        String s = java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE meta.data_open_grant SET app_key=?, app_secret=? WHERE id=?", k, s, id);
        return Map.of("success", true, "app_key", k, "app_secret", s);
    }

    // ---- 工具 ----
    @SuppressWarnings("unchecked")
    private List<String> parseStrList(Object o) {
        if (o instanceof List) {
            List<String> r = new ArrayList<>();
            for (Object x : (List<Object>) o) r.add(String.valueOf(x));
            return r;
        }
        return List.of();
    }

    private Timestamp parseTs(Object o) {
        if (o == null || String.valueOf(o).isEmpty()) return null;
        String s = String.valueOf(o).replace("T", " ");
        try { return Timestamp.valueOf(s.length() >= 19 ? s.substring(0, 19) : s); }
        catch (Exception e) { try { return Timestamp.valueOf(String.valueOf(o)); } catch (Exception ignored) { return null; } }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private static String currentUser() {
        try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); }
        catch (Exception e) { return "system"; }
    }
}
