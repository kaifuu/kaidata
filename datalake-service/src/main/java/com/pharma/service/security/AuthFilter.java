package com.pharma.service.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 鉴权 + 审计 过滤器（数据安全域核心）
 * <p>
 * ① 放行登录接口 /api/auth/login；其余 /api/** 必须携带合法 Bearer 令牌，否则 401。
 * ② 校验通过则把用户载荷放进 AuthContext，供 Controller 获取当前用户。
 * ③ 每次请求落一条审计日志到 meta.sys_audit_log（谁、做了什么、结果）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter implements Filter {

    @Autowired
    private JdbcTemplate jdbc;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) req;
        HttpServletResponse hr = (HttpServletResponse) res;
        String uri = http.getRequestURI();

        // 非 /api/ 前缀（静态/错误页等）直接放行
        if (!uri.startsWith("/api/")) { chain.doFilter(req, res); return; }
        // 登录 / 验证码接口免鉴权（在审计 try/finally 之前 return，不写审计）
        if (uri.equals("/api/auth/login") || uri.equals("/api/auth/captcha")) { chain.doFilter(req, res); return; }

        Map<String, Object> payload = TokenUtil.verify(http.getHeader("Authorization"));

        // 令牌无效 → 记审计并直接 401（不能让 Controller 先提交响应）
        if (payload == null) {
            audit(http, "UNAUTHORIZED", null);
            hr.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            hr.setContentType("application/json;charset=UTF-8");
            try { hr.getWriter().write("{\"message\":\"未登录或令牌已过期\"}"); } catch (Exception ignored) {}
            return;
        }

        AuthContext.set(payload);
        try {
            chain.doFilter(req, res);
        } finally {
            audit(http, "OK", payload);
            AuthContext.clear();
        }
    }

    /** 写一条审计日志（失败则忽略，不影响主流程） */
    private void audit(HttpServletRequest http, String result, Map<String, Object> payload) {
        try {
            String username = payload == null ? "anonymous" : String.valueOf(payload.getOrDefault("username", "anonymous"));
            long id = System.currentTimeMillis();
            String ip = clientIp(http);
            jdbc.update(
                    "INSERT INTO meta.sys_audit_log(id, username, uri, method, params, result, ip, ts) " +
                            "VALUES (?,?,?,?,?,?,?,?)",
                    id, username, http.getRequestURI(), http.getMethod(),
                    http.getQueryString(), result, ip,
                    new java.sql.Timestamp(id));
        } catch (Exception ignored) {
            // 审计失败不阻断业务
        }
    }

    private String clientIp(HttpServletRequest r) {
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}
