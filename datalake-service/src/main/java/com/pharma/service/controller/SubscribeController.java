package com.pharma.service.controller;

import com.pharma.service.access.service.OpenGrantService;
import com.pharma.service.security.AccessDeniedException;
import com.pharma.service.security.Authz;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 数据集订阅：消费方提交订阅申请 → 管理员审核 → 通过则自动建 data-open 开放授权(appkey)。
 */
@RestController
@RequestMapping("/api/market/subscribe")
@CrossOrigin(origins = "*")
public class SubscribeController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OpenGrantService grantService;

    /** 提交订阅（可批量：items）。 */
    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String purpose = str(b.get("purpose"));
        String openType = str(b.getOrDefault("open_type", "API"));
        long limitCount = lng(b.get("limit_count"));
        int limitQps = (int) lng(b.get("limit_qps"));
        Timestamp expire = parseTs(b.get("expire_time"));
        String user = currentUser();
        Object itemsObj = b.get("items");
        if (!(itemsObj instanceof List)) return Map.of("success", false, "msg", "无订阅项");
        List<?> items = (List<?>) itemsObj;
        int n = 0;
        for (Object o : items) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> it = (Map<?, ?>) o;
            long assetId = lng(it.get("asset_id"));
            long metaId = lng(it.get("meta_id"));
            String tbl = str(it.get("table_name"));
            long id = System.currentTimeMillis() + n;
            jdbc.update("INSERT INTO meta.portal_subscribe(id, username, asset_id, meta_id, table_name, purpose, open_type, limit_count, limit_qps, expire_time, status, create_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, user, assetId, metaId, tbl, purpose, openType, limitCount, limitQps, expire, "待审", new Timestamp(id));
            if (assetId > 0) jdbc.update("DELETE FROM meta.portal_cart WHERE username=? AND item_type='table' AND item_ref=?", user, String.valueOf(assetId));
            n++;
        }
        return Map.of("success", true, "count", n);
    }

    /** 我的订阅（通过的 JOIN data_open_grant 带 app_secret）。 */
    @GetMapping("/mine")
    public List<Map<String, Object>> mine() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT s.id, s.asset_id, s.table_name, s.purpose, s.open_type, s.limit_count, s.limit_qps, s.expire_time, " +
                "s.status, s.audit_comment, s.app_key, s.grant_id, s.create_time, g.app_secret " +
                "FROM meta.portal_subscribe s LEFT JOIN meta.data_open_grant g ON g.id=s.grant_id " +
                "WHERE s.username=? ORDER BY s.id DESC", currentUser());
    }

    /** 订阅审核列表（待审/全部）。 */
    @GetMapping("/audit-list")
    public List<Map<String, Object>> auditList(@RequestParam(required = false) String status) {
        Authz.require(Authz.SYS_ADMIN);
        String base = "SELECT s.id, s.username, s.asset_id, s.table_name, s.purpose, s.open_type, s.limit_count, s.limit_qps, s.expire_time, " +
                "s.status, s.audit_comment, s.auditor, s.create_time FROM meta.portal_subscribe s";
        if (status != null && !status.isEmpty()) return jdbc.queryForList(base + " WHERE s.status=? ORDER BY s.id DESC", status);
        return jdbc.queryForList(base + " ORDER BY s.id DESC");
    }

    /** 通过：自动建开放授权。 */
    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> sub;
        try { sub = jdbc.queryForMap("SELECT id, username, asset_id, table_name, open_type, limit_count, limit_qps, expire_time, status FROM meta.portal_subscribe WHERE id=?", id); }
        catch (Exception e) { return Map.of("success", false, "msg", "订阅不存在"); }
        if (!"待审".equals(str(sub.get("status")))) return Map.of("success", false, "msg", "订阅已处理");
        OpenGrantService.GrantInput in = new OpenGrantService.GrantInput();
        in.name = "订阅:" + str(sub.get("username")) + "-" + str(sub.get("table_name"));
        in.assetId = lng(sub.get("asset_id"));
        in.openType = str(sub.get("open_type"));
        in.fields = List.of();
        in.paramField = "";
        in.grantee = str(sub.get("username"));
        in.limitCount = lng(sub.get("limit_count"));
        in.limitQps = (int) lng(sub.get("limit_qps"));
        in.expireTime = toTs(sub.get("expire_time"));
        in.createBy = currentUser();
        OpenGrantService.GrantResult r;
        try { r = grantService.create(in); }
        catch (AccessDeniedException e) { return Map.of("success", false, "msg", e.getMessage()); }
        String cmt = b == null ? "" : str(b.get("comment"));
        jdbc.update("UPDATE meta.portal_subscribe SET status='通过', grant_id=?, app_key=?, auditor=?, audit_comment=?, audit_time=? WHERE id=?",
                r.id, r.appKey, currentUser(), cmt, new Timestamp(System.currentTimeMillis()), id);
        return Map.of("success", true, "app_key", r.appKey);
    }

    /** 驳回。 */
    @PostMapping("/reject")
    public Map<String, Object> reject(@RequestParam long id, @RequestBody(required = false) Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        String cmt = b == null ? "" : str(b.get("comment"));
        jdbc.update("UPDATE meta.portal_subscribe SET status='驳回', auditor=?, audit_comment=?, audit_time=? WHERE id=?",
                currentUser(), cmt, new Timestamp(System.currentTimeMillis()), id);
        return Map.of("success", true);
    }

    // ---- 助手 ----
    private Timestamp parseTs(Object o) {
        if (o == null || String.valueOf(o).isEmpty()) return null;
        String s = String.valueOf(o).replace("T", " ");
        try { return Timestamp.valueOf(s.length() >= 19 ? s.substring(0, 19) : s); }
        catch (Exception e) { return null; }
    }
    private static Timestamp toTs(Object o) {
        if (o == null) return null;
        if (o instanceof Timestamp) return (Timestamp) o;
        if (o instanceof java.util.Date) return new Timestamp(((java.util.Date) o).getTime());
        String s = String.valueOf(o).replace("T", " ");
        try { return Timestamp.valueOf(s.length() >= 19 ? s.substring(0, 19) : s); } catch (Exception e) { return null; }
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static String currentUser() { try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); } catch (Exception e) { return "system"; } }
}
