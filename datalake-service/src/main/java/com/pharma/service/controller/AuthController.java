package com.pharma.service.controller;

import com.pharma.service.security.AuthContext;
import com.pharma.service.security.CaptchaStore;
import com.pharma.service.security.CaptchaUtil;
import com.pharma.service.security.I18nUtil;
import com.pharma.service.security.PasswordUtil;
import com.pharma.service.security.TokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 认证接口（数据安全域）
 * <p>
 * login 查 meta.sys_user 校验密码 → 签发 HMAC 令牌 → 返回用户、角色、可见菜单（供前端动态侧栏）。
 * 其余接口由 AuthFilter 校验令牌；menus/info 读取当前登录上下文。
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaptchaStore captchaStore;

    /** 图形验证码：生成 4 位字符图（免鉴权），前端进入登录页即拉取 */
    @GetMapping("/captcha")
    public Map<String, String> captcha() {
        CaptchaUtil.CaptchaImg c = CaptchaUtil.generate();
        String id = captchaStore.create(c.code());
        return Map.of("captchaId", id, "img", c.img());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        // 验证码校验（查库之前）：失败即拒，挡住无效请求、减少库压力
        if (!captchaStore.verify(body.get("captchaId"), body.get("captchaCode"))) {
            return unauthorized(I18nUtil.message("auth.captcha.error"));
        }
        String username = body.get("username");
        String password = body.get("password");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password, name, status FROM meta.sys_user WHERE username=?", username);
        if (rows.isEmpty()) return unauthorized(I18nUtil.message("auth.login.failed"));
        Map<String, Object> u = rows.get(0);
        if (!"NORMAL".equals(String.valueOf(u.get("status")))) return unauthorized(I18nUtil.message("auth.login.disabled"));
        if (!PasswordUtil.matches(password, String.valueOf(u.get("password")))) return unauthorized(I18nUtil.message("auth.login.failed"));

        long uid = ((Number) u.get("id")).longValue();
        List<String> codes = roleCodesOf(uid);
        String role = codes.isEmpty() ? "GUEST" : codes.get(0);
        String rolesCsv = codes.isEmpty() ? "GUEST" : String.join(",", codes);
        String name = String.valueOf(u.get("name"));
        String token = TokenUtil.issue(username, name, role, rolesCsv);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("username", username, "name", name, "role", role, "roles", codes),
                "menus", menusOf(username)
        ));
    }

    /** 当前登录用户信息（需令牌）：含多角色 / 租户 / 组织 / 状态 / 创建时间 */
    @GetMapping("/info")
    public ResponseEntity<?> info() {
        String username = AuthContext.username();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.username, u.name, u.status, u.create_time, " +
                        "t.name AS tenant_name, o.name AS org_name " +
                        "FROM meta.sys_user u " +
                        "LEFT JOIN meta.sys_tenant t ON t.id = u.tenant_id " +
                        "LEFT JOIN meta.sys_org o ON o.id = u.org_id " +
                        "WHERE u.username = ?", username);
        if (rows.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", I18nUtil.message("auth.user.notFound")));
        Map<String, Object> u = rows.get(0);
        long uid = ((Number) u.get("id")).longValue();
        List<String> roles = roleCodesOf(uid);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("username", u.get("username"));
        result.put("name", u.get("name"));
        result.put("status", u.get("status"));
        result.put("create_time", String.valueOf(u.get("create_time")));
        result.put("tenant_name", u.get("tenant_name"));
        result.put("org_name", u.get("org_name"));
        result.put("role", roles.isEmpty() ? "GUEST" : roles.get(0));
        result.put("roles", roles);
        return ResponseEntity.ok(result);
    }

    /** 修改自己的密码：校验原密码 → 写入新密码哈希 */
    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body) {
        String oldPwd = body.get("oldPassword");
        String newPwd = body.get("newPassword");
        if (oldPwd == null || newPwd == null || newPwd.length() < 6)
            return ResponseEntity.badRequest().body(Map.of("message", I18nUtil.message("auth.password.tooShort")));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT password FROM meta.sys_user WHERE username = ?", AuthContext.username());
        if (rows.isEmpty()) return unauthorized(I18nUtil.message("auth.user.notFound"));
        String hashed = String.valueOf(rows.get(0).get("password"));
        if (!PasswordUtil.matches(oldPwd, hashed))
            return ResponseEntity.badRequest().body(Map.of("message", I18nUtil.message("auth.password.oldWrong")));
        jdbc.update("UPDATE meta.sys_user SET password = ? WHERE username = ?",
                PasswordUtil.hash(newPwd), AuthContext.username());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 我的操作日志（只查自己，自服务） */
    @GetMapping("/logs")
    public List<Map<String, Object>> myLogs(@RequestParam(defaultValue = "20") int limit) {
        if (limit > 100) limit = 100;
        return jdbc.queryForList(
                "SELECT id, username, action, uri, method, params, result, ip, ts " +
                        "FROM meta.sys_audit_log WHERE username = ? ORDER BY ts DESC LIMIT ?",
                AuthContext.username(), limit);
    }

    /** 我的待办计数：未处理告警 / 待审资产 / 质量异常 */
    @GetMapping("/todo")
    public Map<String, Object> myTodo() {
        return Map.of(
                "alerts", cnt("SELECT COUNT(*) FROM meta.sec_alert_event WHERE status = '未处理'"),
                "assets", cnt("SELECT COUNT(*) FROM meta.asset WHERE status = '待审'"),
                "quality", cnt("SELECT COUNT(*) FROM meta.gov_quality_result WHERE status = 'FAIL'"));
    }

    private long cnt(String sql) {
        try { return jdbc.queryForObject(sql, Long.class); }
        catch (Exception e) { return 0; }
    }

    /** 当前用户的可见菜单（动态侧栏） */
    @GetMapping("/menus")
    public List<Map<String, Object>> menus() {
        return menusOf(AuthContext.username());
    }

    /** 登出（客户端丢弃令牌；服务端无状态） */
    @PostMapping("/logout")
    public Map<String, Object> logout() {
        return Map.of("success", true);
    }

    // -------- 辅助 --------

    private List<String> roleCodesOf(long userId) {
        try {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT r.code FROM meta.sys_role r JOIN meta.sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=?",
                    userId);
            return r.stream().map(m -> String.valueOf(m.get("code"))).toList();
        } catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> menusOf(String username) {
        // 一个用户可能持多个角色，而同一菜单可能授权给其中多个角色 → JOIN 会产生重复行，
        // 用 DISTINCT 按菜单去重，避免侧栏同一菜单渲染多次。
        return jdbc.queryForList(
                "SELECT DISTINCT m.id, m.parent_id, m.name, m.path, m.icon, m.perm, m.type, m.sort " +
                        "FROM meta.sys_menu m " +
                        "JOIN meta.sys_role_menu rm ON rm.menu_id = m.id " +
                        "JOIN meta.sys_user_role ur ON ur.role_id = rm.role_id " +
                        "JOIN meta.sys_user u ON u.id = ur.user_id " +
                        "WHERE u.username = ? AND (m.status = 'ENABLED' OR m.status IS NULL) ORDER BY m.sort, m.id", username);
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String msg) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", msg));
    }
}
