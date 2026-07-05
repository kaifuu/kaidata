package com.pharma.service.controller;

import com.pharma.service.security.AuthContext;
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

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, password, name, status FROM meta.sys_user WHERE username=?", username);
        if (rows.isEmpty()) return unauthorized("账号或密码错误");
        Map<String, Object> u = rows.get(0);
        if (!"NORMAL".equals(String.valueOf(u.get("status")))) return unauthorized("账号已停用");
        if (!PasswordUtil.matches(password, String.valueOf(u.get("password")))) return unauthorized("账号或密码错误");

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

    /** 当前登录用户信息（需令牌） */
    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "username", AuthContext.username(),
                "name", AuthContext.get().getOrDefault("name", ""),
                "role", AuthContext.get().getOrDefault("role", "")
        );
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
                        "WHERE u.username = ? ORDER BY m.sort, m.id", username);
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String msg) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", msg));
    }
}
