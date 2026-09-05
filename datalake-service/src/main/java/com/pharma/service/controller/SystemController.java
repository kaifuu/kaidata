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
import java.util.LinkedHashMap;
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
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    /** 接口/操作审计：op=write 时只看写操作（POST/PUT/DELETE），begin/end 为时间范围 */
    @GetMapping("/log")
    public PageResult<SysAuditLog> logs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String op,
            @RequestParam(required = false) String begin,
            @RequestParam(required = false) String end) {
        Authz.require(Authz.AUDIT_ADMIN);
        Map<String, Object> q = new HashMap<>();
        q.put("username", username);
        q.put("result", result);
        q.put("keyword", keyword);
        q.put("op", op);
        q.put("begin", begin);
        q.put("end", end);
        return auditLogService.pageLogs(page, size, q);
    }

    /** 登录日志：SUCCESS / FAIL(原因见 msg) / LOGOUT */
    @GetMapping("/login-log")
    public Map<String, Object> loginLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String begin,
            @RequestParam(required = false) String end) {
        Authz.require(Authz.AUDIT_ADMIN);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isEmpty()) { where.append(" AND username=?"); args.add(username); }
        if (result != null && !result.isEmpty()) { where.append(" AND result=?"); args.add(result); }
        if (begin != null && !begin.isEmpty()) { where.append(" AND ts>=?"); args.add(begin); }
        if (end != null && !end.isEmpty()) { where.append(" AND ts<=?"); args.add(end); }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM meta.sys_login_log" + where, Long.class, args.toArray());
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT id, username, result, msg, ip, ts FROM meta.sys_login_log" + where +
                        " ORDER BY ts DESC, id DESC LIMIT " + ((page - 1) * size) + "," + size, args.toArray());
        return Map.of("records", records, "total", total, "page", page, "size", size);
    }

    // ==================== 配置管理·系统品牌 [SYS_ADMIN] ====================
    // 登录页/首页的基础信息（系统名/LOGO/ICON/ICP 等），k-v 存 meta.sys_config；空值=前端回退内置默认

    /** 全量配置（管理端）：values 为 key→当前值，rows 为 key→remark/更新时间。 */
    @GetMapping("/config")
    public Map<String, Object> configAll() {
        Authz.require(Authz.SYS_ADMIN);
        Map<String, Object> values = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT cfg_key, cfg_value, remark, update_time FROM meta.sys_config ORDER BY id");
        for (Map<String, Object> r : rows) values.put(String.valueOf(r.get("cfg_key")), r.get("cfg_value"));
        return Map.of("values", values, "rows", rows);
    }

    /** 批量保存：body={key: value}；存在则 UPDATE 否则 INSERT（键白名单外也可存，便于扩展）。 */
    @PutMapping("/config")
    public Map<String, Object> configSave(@RequestBody Map<String, Object> b) {
        Authz.require(Authz.SYS_ADMIN);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        for (Map.Entry<String, Object> e : b.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            Integer exist = jdbc.queryForObject("SELECT COUNT(*) FROM meta.sys_config WHERE cfg_key=?", Integer.class, e.getKey());
            if (exist != null && exist > 0)
                jdbc.update("UPDATE meta.sys_config SET cfg_value=?, update_time=? WHERE cfg_key=?", v, now, e.getKey());
            else
                jdbc.update("INSERT INTO meta.sys_config(id, cfg_key, cfg_value, update_time) VALUES (?,?,?,?)",
                        System.currentTimeMillis(), e.getKey(), v, now);
        }
        return Map.of("success", true);
    }

    /** 品牌信息（公开：登录页未登录也需展示，AuthFilter 白名单放行；仅暴露品牌键，不带其他配置）。 */
    @GetMapping("/brand")
    public Map<String, Object> brand() {
        List<String> pub = List.of("sys.name", "sys.name_en", "sys.slogan", "sys.logo", "sys.icon", "sys.icp", "sys.copyright");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT cfg_key, cfg_value FROM meta.sys_config")) {
            String k = String.valueOf(r.get("cfg_key"));
            if (pub.contains(k)) out.put(k, r.get("cfg_value"));
        }
        return out;
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
