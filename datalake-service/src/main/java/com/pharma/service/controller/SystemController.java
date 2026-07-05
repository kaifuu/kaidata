package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import com.pharma.service.security.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 系统管理（三员分立）
 * <p>
 * 六大域，各自仅对职责角色开放（Authz.require 在入口强制，越权 → 403）：
 * <ul>
 *   <li>SYS_ADMIN  系统管理员   → 用户 / 组织 / 租户（账户与组织生命周期）</li>
 *   <li>SEC_ADMIN  安全保密管理员 → 角色 / 菜单 / 授权（角色-菜单、角色-用户，含给用户授角色）</li>
 *   <li>AUDIT_ADMIN 安全审计员   → 日志（只读）</li>
 * </ul>
 * 逻辑连贯：租户 ⟶ 组织(归属租户) ⟶ 用户(归属租户+组织)；菜单定义可见功能；
 * 角色绑定菜单(权限) 并拥有成员(用户)；用户经角色获得菜单；全程审计。
 * <p>
 * 注：用户管理不直接分配角色——角色授予是安全员的职责（角色管理页「成员」）。
 */
@RestController
@RequestMapping("/api/system")
@CrossOrigin(origins = "*")
public class SystemController {

    @Autowired
    private JdbcTemplate jdbc;

    // 受保护账号，禁止删除（保证三员演示账号常在）
    private static final Set<String> PROTECTED = Set.of("admin", "sysadmin", "secadmin", "audadmin");

    // ==================== 用户管理 [SYS_ADMIN] ====================

    /** 用户列表（含租户/组织名、角色码）。SYS_ADMIN 与 SEC_ADMIN 均可读（安全员需选成员）。 */
    @GetMapping("/user")
    public List<Map<String, Object>> listUsers() {
        Authz.require(Authz.SYS_ADMIN, Authz.SEC_ADMIN);
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT u.id, u.username, u.name, u.status, u.tenant_id, u.org_id, u.create_time, " +
                        "t.name AS tenant_name, o.name AS org_name " +
                        "FROM meta.sys_user u " +
                        "LEFT JOIN meta.sys_tenant t ON t.id = u.tenant_id " +
                        "LEFT JOIN meta.sys_org o ON o.id = u.org_id " +
                        "ORDER BY u.id");
        // 角色码按用户聚合
        Map<Long, List<String>> roleMap = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT ur.user_id AS uid, r.code AS code FROM meta.sys_user_role ur " +
                        "JOIN meta.sys_role r ON r.id = ur.role_id")) {
            roleMap.computeIfAbsent(((Number) r.get("uid")).longValue(), k -> new ArrayList<>())
                    .add(String.valueOf(r.get("code")));
        }
        for (Map<String, Object> u : users) {
            long uid = ((Number) u.get("id")).longValue();
            u.put("roles", roleMap.getOrDefault(uid, List.of()));
        }
        return users;
    }

    @PostMapping("/user")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.sys_user(id, username, password, name, status, tenant_id, org_id, create_time) " +
                        "VALUES (?,?,?,?,?,?,?,?)",
                id, body.get("username"),
                PasswordUtil.hash(String.valueOf(body.getOrDefault("password", ""))),
                body.getOrDefault("name", ""), body.getOrDefault("status", "NORMAL"),
                num(body.get("tenant_id")), num(body.get("org_id")), new java.sql.Timestamp(id));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/user")
    public Map<String, Object> updateUser(@RequestBody Map<String, Object> body) {
        Authz.require(Authz.SYS_ADMIN);
        long id = ((Number) body.get("id")).longValue();
        Object pwd = body.get("password");
        if (pwd != null && !String.valueOf(pwd).isEmpty()) {
            jdbc.update("UPDATE meta.sys_user SET name=?, status=?, tenant_id=?, org_id=?, password=? WHERE id=?",
                    body.getOrDefault("name", ""), body.getOrDefault("status", "NORMAL"),
                    num(body.get("tenant_id")), num(body.get("org_id")),
                    PasswordUtil.hash(String.valueOf(pwd)), id);
        } else {
            jdbc.update("UPDATE meta.sys_user SET name=?, status=?, tenant_id=?, org_id=? WHERE id=?",
                    body.getOrDefault("name", ""), body.getOrDefault("status", "NORMAL"),
                    num(body.get("tenant_id")), num(body.get("org_id")), id);
        }
        return Map.of("success", true);
    }

    @DeleteMapping("/user")
    public Map<String, Object> deleteUser(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        String username = jdbc.queryForObject("SELECT username FROM meta.sys_user WHERE id=?", new Object[]{id}, String.class);
        if (username != null && PROTECTED.contains(username)) {
            throw new com.pharma.service.security.AccessDeniedException("受保护账号禁止删除：" + username);
        }
        jdbc.update("DELETE FROM meta.sys_user_role WHERE user_id=?", id);
        jdbc.update("DELETE FROM meta.sys_user WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 组织管理 [SYS_ADMIN] ====================

    @GetMapping("/org")
    public List<Map<String, Object>> listOrgs(@RequestParam(required = false) Long tenantId) {
        Authz.require(Authz.SYS_ADMIN);
        String sql = "SELECT o.id, o.tenant_id, o.parent_id, o.code, o.name, o.sort, o.create_time, t.name AS tenant_name " +
                "FROM meta.sys_org o LEFT JOIN meta.sys_tenant t ON t.id=o.tenant_id";
        List<Map<String, Object>> rows = (tenantId == null)
                ? jdbc.queryForList(sql + " ORDER BY o.tenant_id, o.sort, o.id")
                : jdbc.queryForList(sql + " WHERE o.tenant_id=? ORDER BY o.sort, o.id", tenantId);
        // 各组织人数
        Map<Long, Long> cnt = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT org_id, COUNT(*) c FROM meta.sys_user WHERE org_id IS NOT NULL GROUP BY org_id")) {
            cnt.put(((Number) r.get("org_id")).longValue(), ((Number) r.get("c")).longValue());
        }
        for (Map<String, Object> o : rows) {
            o.put("user_count", cnt.getOrDefault(((Number) o.get("id")).longValue(), 0L));
        }
        return rows;
    }

    @PostMapping("/org")
    public Map<String, Object> createOrg(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.sys_org(id, tenant_id, parent_id, code, name, sort, create_time) VALUES (?,?,?,?,?,?,?)",
                id, num(b.get("tenant_id")), num(b.get("parent_id")), b.getOrDefault("code", ""),
                b.getOrDefault("name", ""), intVal(b.get("sort"), 1), new java.sql.Timestamp(id));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/org")
    public Map<String, Object> updateOrg(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = ((Number) b.get("id")).longValue();
        jdbc.update("UPDATE meta.sys_org SET tenant_id=?, parent_id=?, code=?, name=?, sort=? WHERE id=?",
                num(b.get("tenant_id")), num(b.get("parent_id")), b.getOrDefault("code", ""),
                b.getOrDefault("name", ""), intVal(b.get("sort"), 1), id);
        return Map.of("success", true);
    }

    @DeleteMapping("/org")
    public Map<String, Object> deleteOrg(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        // 有子组织或用户则拒绝
        long child = jdbc.queryForObject("SELECT COUNT(*) FROM meta.sys_org WHERE parent_id=?", Long.class, id);
        long u = jdbc.queryForObject("SELECT COUNT(*) FROM meta.sys_user WHERE org_id=?", Long.class, id);
        if (child > 0 || u > 0) {
            throw new com.pharma.service.security.AccessDeniedException("该组织下存在子组织或用户，禁止删除");
        }
        jdbc.update("DELETE FROM meta.sys_org WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 租户管理 [SYS_ADMIN] ====================

    @GetMapping("/tenant")
    public List<Map<String, Object>> listTenants() {
        Authz.require(Authz.SYS_ADMIN);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, code, name, status, create_time FROM meta.sys_tenant ORDER BY id");
        Map<Long, Long> orgCnt = new HashMap<>(), userCnt = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT tenant_id, COUNT(*) c FROM meta.sys_org GROUP BY tenant_id"))
            orgCnt.put(((Number) r.get("tenant_id")).longValue(), ((Number) r.get("c")).longValue());
        for (Map<String, Object> r : jdbc.queryForList("SELECT tenant_id, COUNT(*) c FROM meta.sys_user WHERE tenant_id IS NOT NULL GROUP BY tenant_id"))
            userCnt.put(((Number) r.get("tenant_id")).longValue(), ((Number) r.get("c")).longValue());
        for (Map<String, Object> t : rows) {
            long tid = ((Number) t.get("id")).longValue();
            t.put("org_count", orgCnt.getOrDefault(tid, 0L));
            t.put("user_count", userCnt.getOrDefault(tid, 0L));
        }
        return rows;
    }

    @PostMapping("/tenant")
    public Map<String, Object> createTenant(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.sys_tenant(id, code, name, status, create_time) VALUES (?,?,?,?,?)",
                id, b.getOrDefault("code", ""), b.getOrDefault("name", ""), b.getOrDefault("status", "NORMAL"), new java.sql.Timestamp(id));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/tenant")
    public Map<String, Object> updateTenant(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        long id = ((Number) b.get("id")).longValue();
        jdbc.update("UPDATE meta.sys_tenant SET code=?, name=?, status=? WHERE id=?",
                b.getOrDefault("code", ""), b.getOrDefault("name", ""), b.getOrDefault("status", "NORMAL"), id);
        return Map.of("success", true);
    }

    @DeleteMapping("/tenant")
    public Map<String, Object> deleteTenant(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        long u = jdbc.queryForObject("SELECT COUNT(*) FROM meta.sys_user WHERE tenant_id=?", Long.class, id);
        if (u > 0) throw new com.pharma.service.security.AccessDeniedException("该租户下存在用户，禁止删除");
        jdbc.update("DELETE FROM meta.sys_org WHERE tenant_id=?", id);
        jdbc.update("DELETE FROM meta.sys_tenant WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 角色管理 [SEC_ADMIN] ====================

    /** 角色列表（含已授权菜单 id、成员用户 id）。 */
    @GetMapping("/role")
    public List<Map<String, Object>> listRoles() {
        Authz.require(Authz.SEC_ADMIN);
        List<Map<String, Object>> roles = jdbc.queryForList("SELECT id, code, name FROM meta.sys_role ORDER BY id");
        Map<Long, List<Long>> menuMap = new HashMap<>(), userMap = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT role_id, menu_id FROM meta.sys_role_menu"))
            menuMap.computeIfAbsent(((Number) r.get("role_id")).longValue(), k -> new ArrayList<>())
                    .add(((Number) r.get("menu_id")).longValue());
        for (Map<String, Object> r : jdbc.queryForList("SELECT user_id, role_id FROM meta.sys_user_role"))
            userMap.computeIfAbsent(((Number) r.get("role_id")).longValue(), k -> new ArrayList<>())
                    .add(((Number) r.get("user_id")).longValue());
        for (Map<String, Object> role : roles) {
            long rid = ((Number) role.get("id")).longValue();
            role.put("menu_ids", menuMap.getOrDefault(rid, List.of()));
            role.put("user_ids", userMap.getOrDefault(rid, List.of()));
        }
        return roles;
    }

    @PostMapping("/role")
    public Map<String, Object> createRole(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.sys_role(id, code, name) VALUES (?,?,?)",
                id, b.getOrDefault("code", ""), b.getOrDefault("name", ""));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/role")
    public Map<String, Object> updateRole(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long id = ((Number) b.get("id")).longValue();
        jdbc.update("UPDATE meta.sys_role SET code=?, name=? WHERE id=?", b.getOrDefault("code", ""), b.getOrDefault("name", ""), id);
        return Map.of("success", true);
    }

    @DeleteMapping("/role")
    public Map<String, Object> deleteRole(@RequestParam long id) {
        Authz.require(Authz.SEC_ADMIN);
        if (id <= 3) throw new com.pharma.service.security.AccessDeniedException("内置三员角色禁止删除");
        jdbc.update("DELETE FROM meta.sys_role_menu WHERE role_id=?", id);
        jdbc.update("DELETE FROM meta.sys_user_role WHERE role_id=?", id);
        jdbc.update("DELETE FROM meta.sys_role WHERE id=?", id);
        return Map.of("success", true);
    }

    /** 角色-菜单授权（整体替换）。 */
    @PutMapping("/role/menus")
    public Map<String, Object> grantMenus(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long roleId = ((Number) b.get("roleId")).longValue();
        jdbc.update("DELETE FROM meta.sys_role_menu WHERE role_id=?", roleId);
        for (Object mid : (List<?>) b.getOrDefault("menuIds", List.of())) {
            jdbc.update("INSERT INTO meta.sys_role_menu(role_id, menu_id) VALUES (?,?)", roleId, ((Number) mid).longValue());
        }
        return Map.of("success", true);
    }

    /** 角色-用户授权（整体替换：设置该角色的成员）。 */
    @PutMapping("/role/users")
    public Map<String, Object> grantUsers(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long roleId = ((Number) b.get("roleId")).longValue();
        if (roleId <= 3) throw new com.pharma.service.security.AccessDeniedException("内置三员角色成员由系统维护，禁止在此调整");
        jdbc.update("DELETE FROM meta.sys_user_role WHERE role_id=?", roleId);
        for (Object uid : (List<?>) b.getOrDefault("userIds", List.of())) {
            jdbc.update("INSERT INTO meta.sys_user_role(user_id, role_id) VALUES (?,?)", ((Number) uid).longValue(), roleId);
        }
        return Map.of("success", true);
    }

    // ==================== 菜单管理 [SEC_ADMIN] ====================

    @GetMapping("/menu")
    public List<Map<String, Object>> listMenus() {
        Authz.require(Authz.SEC_ADMIN);
        return jdbc.queryForList("SELECT id, parent_id, name, path, icon, perm, type, sort FROM meta.sys_menu ORDER BY sort, id");
    }

    @PostMapping("/menu")
    public Map<String, Object> createMenu(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long id = System.currentTimeMillis();
        jdbc.update("INSERT INTO meta.sys_menu(id, parent_id, name, path, icon, perm, type, sort) VALUES (?,?,?,?,?,?,?,?)",
                id, num(b.get("parent_id")), b.getOrDefault("name", ""), b.getOrDefault("path", ""),
                b.getOrDefault("icon", "Menu"), b.getOrDefault("perm", ""), b.getOrDefault("type", "MENU"),
                intVal(b.get("sort"), 99));
        return Map.of("success", true, "id", id);
    }

    @PutMapping("/menu")
    public Map<String, Object> updateMenu(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long id = ((Number) b.get("id")).longValue();
        jdbc.update("UPDATE meta.sys_menu SET parent_id=?, name=?, path=?, icon=?, perm=?, type=?, sort=? WHERE id=?",
                num(b.get("parent_id")), b.getOrDefault("name", ""), b.getOrDefault("path", ""),
                b.getOrDefault("icon", "Menu"), b.getOrDefault("perm", ""), b.getOrDefault("type", "MENU"),
                intVal(b.get("sort"), 99), id);
        return Map.of("success", true);
    }

    @DeleteMapping("/menu")
    public Map<String, Object> deleteMenu(@RequestParam long id) {
        Authz.require(Authz.SEC_ADMIN);
        long child = jdbc.queryForObject("SELECT COUNT(*) FROM meta.sys_menu WHERE parent_id=?", Long.class, id);
        if (child > 0) throw new com.pharma.service.security.AccessDeniedException("存在子菜单，禁止删除");
        jdbc.update("DELETE FROM meta.sys_role_menu WHERE menu_id=?", id);
        jdbc.update("DELETE FROM meta.sys_menu WHERE id=?", id);
        return Map.of("success", true);
    }

    // ==================== 日志管理 [AUDIT_ADMIN] ====================

    @GetMapping("/log")
    public List<Map<String, Object>> logs(@RequestParam(required = false) String username,
                                          @RequestParam(required = false) String result,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(defaultValue = "200") int limit) {
        Authz.require(Authz.AUDIT_ADMIN);
        StringBuilder sql = new StringBuilder("SELECT id, username, uri, method, params, result, ip, ts FROM meta.sys_audit_log WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isEmpty()) { sql.append(" AND username=?"); args.add(username); }
        if (result != null && !result.isEmpty()) { sql.append(" AND result=?"); args.add(result); }
        if (keyword != null && !keyword.isEmpty()) { sql.append(" AND (uri LIKE ? OR params LIKE ?)"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%"); }
        sql.append(" ORDER BY ts DESC LIMIT ").append(Math.min(limit, 1000));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // -------- 类型转换助手 --------

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : Long.parseLong(s);
    }

    private static int intVal(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }
}
