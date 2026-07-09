package com.pharma.service.controller;

import com.pharma.service.security.Authz;
import com.pharma.service.system.entity.SysAuditLog;
import com.pharma.service.system.entity.SysMenu;
import com.pharma.service.system.entity.SysOrg;
import com.pharma.service.system.entity.SysRole;
import com.pharma.service.system.entity.SysTenant;
import com.pharma.service.system.entity.SysUser;
import com.pharma.service.system.service.AuditLogService;
import com.pharma.service.system.service.MenuService;
import com.pharma.service.system.service.OrgService;
import com.pharma.service.system.service.RoleService;
import com.pharma.service.system.service.TenantService;
import com.pharma.service.system.service.UserService;
import com.pharma.service.system.vo.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统管理（三员分立）—— MyBatis-Plus 实现。
 * <p>
 * 六大域，各自仅对职责角色开放（Authz.require 在入口强制，越权 → 403）：
 * <ul>
 *   <li>SYS_ADMIN  系统管理员   → 用户 / 组织 / 租户</li>
 *   <li>SEC_ADMIN  安全保密管理员 → 角色 / 菜单 / 授权</li>
 *   <li>AUDIT_ADMIN 安全审计员   → 日志（只读）</li>
 * </ul>
 * 控制层只做鉴权 + 参数装配，分页/聚合/级联逻辑下沉到 system.service。
 * 列表端点统一返回 {@link PageResult}（菜单除外，返回树）。
 */
@RestController
@RequestMapping("/api/system")
@CrossOrigin(origins = "*")
public class SystemController {

    @Autowired
    private UserService userService;
    @Autowired
    private OrgService orgService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private MenuService menuService;
    @Autowired
    private AuditLogService auditLogService;

    // ==================== 用户管理 [SYS_ADMIN] ====================

    /** 用户列表（分页 + 检索）。SYS_ADMIN 与 SEC_ADMIN 均可读（安全员需选成员）。 */
    @GetMapping("/user")
    public PageResult<SysUser> listUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long orgId,
            @RequestParam(required = false) String status) {
        Authz.require(Authz.SYS_ADMIN, Authz.SEC_ADMIN);
        Map<String, Object> q = new HashMap<>();
        q.put("username", username);
        q.put("name", name);
        q.put("tenantId", tenantId);
        q.put("orgId", orgId);
        q.put("status", status);
        return userService.pageUsers(page, size, q);
    }

    @PostMapping("/user")
    public Map<String, Object> createUser(@RequestBody SysUser u) {
        Authz.require(Authz.SYS_ADMIN);
        return Map.of("success", true, "id", userService.createUser(u));
    }

    @PutMapping("/user")
    public Map<String, Object> updateUser(@RequestBody SysUser u) {
        Authz.require(Authz.SYS_ADMIN);
        userService.updateUser(u);
        return Map.of("success", true);
    }

    @DeleteMapping("/user")
    public Map<String, Object> deleteUser(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        userService.deleteUser(id);
        return Map.of("success", true);
    }

    // ==================== 组织管理 [SYS_ADMIN] ====================

    @GetMapping("/org")
    public PageResult<SysOrg> listOrgs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String keyword) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> q = new HashMap<>();
        q.put("tenantId", tenantId);
        q.put("keyword", keyword);
        return orgService.pageOrgs(page, size, q);
    }

    @PostMapping("/org")
    public Map<String, Object> createOrg(@RequestBody SysOrg o) {
        Authz.require(Authz.SYS_ADMIN);
        return Map.of("success", true, "id", orgService.createOrg(o));
    }

    @PutMapping("/org")
    public Map<String, Object> updateOrg(@RequestBody SysOrg o) {
        Authz.require(Authz.SYS_ADMIN);
        orgService.updateOrg(o);
        return Map.of("success", true);
    }

    @DeleteMapping("/org")
    public Map<String, Object> deleteOrg(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        orgService.deleteOrg(id);
        return Map.of("success", true);
    }

    // ==================== 租户管理 [SYS_ADMIN] ====================

    @GetMapping("/tenant")
    public PageResult<SysTenant> listTenants(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> q = new HashMap<>();
        q.put("keyword", keyword);
        q.put("status", status);
        return tenantService.pageTenants(page, size, q);
    }

    @PostMapping("/tenant")
    public Map<String, Object> createTenant(@RequestBody SysTenant t) {
        Authz.require(Authz.SYS_ADMIN);
        return Map.of("success", true, "id", tenantService.createTenant(t));
    }

    @PutMapping("/tenant")
    public Map<String, Object> updateTenant(@RequestBody SysTenant t) {
        Authz.require(Authz.SYS_ADMIN);
        tenantService.updateTenant(t);
        return Map.of("success", true);
    }

    @DeleteMapping("/tenant")
    public Map<String, Object> deleteTenant(@RequestParam long id) {
        Authz.require(Authz.SYS_ADMIN);
        tenantService.deleteTenant(id);
        return Map.of("success", true);
    }

    // ==================== 角色管理 [SEC_ADMIN] ====================

    @GetMapping("/role")
    public PageResult<SysRole> listRoles(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String keyword) {
        Authz.require(Authz.SEC_ADMIN);
        Map<String, Object> q = new HashMap<>();
        q.put("keyword", keyword);
        return roleService.pageRoles(page, size, q);
    }

    @PostMapping("/role")
    public Map<String, Object> createRole(@RequestBody SysRole r) {
        Authz.require(Authz.SEC_ADMIN);
        return Map.of("success", true, "id", roleService.createRole(r));
    }

    @PutMapping("/role")
    public Map<String, Object> updateRole(@RequestBody SysRole r) {
        Authz.require(Authz.SEC_ADMIN);
        roleService.updateRole(r);
        return Map.of("success", true);
    }

    @DeleteMapping("/role")
    public Map<String, Object> deleteRole(@RequestParam long id) {
        Authz.require(Authz.SEC_ADMIN);
        roleService.deleteRole(id);
        return Map.of("success", true);
    }

    /** 角色-菜单授权（整体替换）。 */
    @PutMapping("/role/menus")
    public Map<String, Object> grantMenus(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long roleId = ((Number) b.get("roleId")).longValue();
        roleService.grantMenus(roleId, toLongList(b.get("menuIds")));
        return Map.of("success", true);
    }

    /** 角色-用户授权（整体替换：设置该角色的成员）。 */
    @PutMapping("/role/users")
    public Map<String, Object> grantUsers(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SEC_ADMIN);
        long roleId = ((Number) b.get("roleId")).longValue();
        roleService.grantUsers(roleId, toLongList(b.get("userIds")));
        return Map.of("success", true);
    }

    // ==================== 菜单管理 [SEC_ADMIN] ====================

    /** 菜单树（不分页，全量树，前端按关键字过滤）。 */
    @GetMapping("/menu")
    public List<SysMenu> listMenus() {
        Authz.require(Authz.SEC_ADMIN);
        return menuService.listTree();
    }

    @PostMapping("/menu")
    public Map<String, Object> createMenu(@RequestBody SysMenu m) {
        Authz.require(Authz.SEC_ADMIN);
        return Map.of("success", true, "id", menuService.createMenu(m));
    }

    @PutMapping("/menu")
    public Map<String, Object> updateMenu(@RequestBody SysMenu m) {
        Authz.require(Authz.SEC_ADMIN);
        menuService.updateMenu(m);
        return Map.of("success", true);
    }

    /** 菜单启停切换（停用后侧栏不显示）。 */
    @PostMapping("/menu/toggle")
    public Map<String, Object> toggleMenu(@RequestParam long id) {
        Authz.require(Authz.SEC_ADMIN);
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("status", menuService.toggleMenu(id));
        return r;
    }

    @DeleteMapping("/menu")
    public Map<String, Object> deleteMenu(@RequestParam long id) {
        Authz.require(Authz.SEC_ADMIN);
        menuService.deleteMenu(id);
        return Map.of("success", true);
    }

    // ==================== 日志管理 [AUDIT_ADMIN] ====================

    @GetMapping("/log")
    public PageResult<SysAuditLog> logs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword) {
        Authz.require(Authz.AUDIT_ADMIN);
        Map<String, Object> q = new HashMap<>();
        q.put("username", username);
        q.put("result", result);
        q.put("keyword", keyword);
        return auditLogService.pageLogs(page, size, q);
    }

    // -------- 类型转换助手 --------

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (o == null) return List.of();
        List<?> list = (List<?>) o;
        List<Long> r = new ArrayList<>();
        for (Object x : list) r.add(((Number) x).longValue());
        return r;
    }
}
