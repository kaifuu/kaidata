package com.pharma.service.controller;

import com.pharma.service.access.service.DataServiceExecutor;
import com.pharma.service.security.Authz;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/** 数据服务 [管理需 SYS_ADMIN，invoke 需登录]：服务 CRUD + 发布/下线 + 调用 + 统计 + 日志。 */
@RestController
@RequestMapping("/api/data-service")
@CrossOrigin(origins = "*")
public class DataServiceController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataServiceExecutor executor;

    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT id, code, name, sql_text, datasource_id, method, params, path, auth, status, create_time FROM meta.data_service ORDER BY id");
    }
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.data_service(id, code, name, sql_text, datasource_id, method, params, path, auth, status, create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, str(b.get("code")), str(b.get("name")), str(b.get("sql_text")), lng(b.get("datasource_id")),
                str(b.getOrDefault("method", "GET")), str(b.get("params")), str(b.get("path")), bool(b.get("auth")),
                str(b.getOrDefault("status", "DRAFT")), new Timestamp(id));
        return Map.of("success", true, "id", id);
    }
    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("UPDATE meta.data_service SET code=?, name=?, sql_text=?, datasource_id=?, method=?, params=?, path=?, auth=?, status=? WHERE id=?",
                str(b.get("code")), str(b.get("name")), str(b.get("sql_text")), lng(b.get("datasource_id")),
                str(b.get("method")), str(b.get("params")), str(b.get("path")), bool(b.get("auth")), str(b.get("status")), lng(b.get("id")));
        return Map.of("success", true);
    }
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        jdbc.update("DELETE FROM meta.data_service_log WHERE service_id=?", id);
        jdbc.update("DELETE FROM meta.data_service WHERE id=?", id);
        return Map.of("success", true);
    }
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.data_service SET status='PUBLISHED' WHERE id=?", id); return Map.of("success", true); }
    @PostMapping("/unpublish")
    public Map<String, Object> unpublish(@RequestParam long id) { Authz.require(Authz.SYS_ADMIN); jdbc.update("UPDATE meta.data_service SET status='DRAFT' WHERE id=?", id); return Map.of("success", true); }

    /** 调用服务（需登录，AuthFilter 已校验）。query 参数即 SQL {param}。 */
    @GetMapping("/invoke/{code}")
    public Map<String, Object> invoke(@PathVariable String code, @RequestParam Map<String, String> params, HttpServletRequest req) {
        return executor.invoke(code, params, currentUser(), req.getRemoteAddr());
    }

    /** 调用统计（每服务：调用数/平均耗时/成功数）。 */
    @GetMapping("/stats")
    public List<Map<String, Object>> stats() {
        Authz.require(Authz.SYS_ADMIN);
        return jdbc.queryForList("SELECT s.id, s.code, s.name, s.status, COUNT(l.id) calls, COALESCE(AVG(l.cost_ms),0) avg_cost, " +
                "SUM(CASE WHEN l.status='SUCCESS' THEN 1 ELSE 0 END) success " +
                "FROM meta.data_service s LEFT JOIN meta.data_service_log l ON l.service_id=s.id " +
                "GROUP BY s.id, s.code, s.name, s.status ORDER BY calls DESC");
    }

    @GetMapping("/log")
    public List<Map<String, Object>> log(@RequestParam(required = false) Long serviceId) {
        Authz.require(Authz.SYS_ADMIN);
        if (serviceId == null) return jdbc.queryForList("SELECT id, service_id, cost_ms, status, params, ip, caller, call_time FROM meta.data_service_log ORDER BY id DESC LIMIT 200");
        return jdbc.queryForList("SELECT id, service_id, cost_ms, status, params, ip, caller, call_time FROM meta.data_service_log WHERE service_id=? ORDER BY id DESC LIMIT 200", serviceId);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long lng(Object o) { if (o == null) return 0; if (o instanceof Number) return ((Number) o).longValue(); try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; } }
    private static boolean bool(Object o) { return o != null && (Boolean.TRUE.equals(o) || "true".equalsIgnoreCase(String.valueOf(o)) || "1".equals(String.valueOf(o))); }
    private static String currentUser() { try { return String.valueOf(com.pharma.service.security.AuthContext.get().getOrDefault("username", "system")); } catch (Exception e) { return "system"; } }
}
