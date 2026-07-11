package com.pharma.service.controller;

import com.pharma.service.access.service.DataServiceExecutor;
import com.pharma.service.access.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.util.*;

/**
 * 开放 API 调用端点：/openapi/{appKey}
 * <p>AuthFilter 只拦 /api/**，故 /openapi/** 免登录；用 appkey 鉴权。
 * 流程：appkey+secret 鉴权 → 状态/有效期（限时长）→ 内存限流（限次/限流）→ 平台代查。
 */
@RestController
@RequestMapping("/openapi")
@CrossOrigin(origins = "*")
public class OpenApiController {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataServiceExecutor executor;
    @Autowired private RateLimiter rateLimiter;

    @GetMapping("/{appKey}")
    public ResponseEntity<Map<String, Object>> invoke(@PathVariable String appKey,
                                                      @RequestParam Map<String, String> params,
                                                      HttpServletRequest req) {
        Map<String, Object> g;
        try {
            g = jdbc.queryForMap("SELECT app_key, app_secret, grantee, service_code, status, limit_count, limit_qps, expire_time FROM meta.data_open_grant WHERE app_key=?", appKey);
        } catch (Exception e) {
            return resp(HttpStatus.UNAUTHORIZED, "无效的 appKey");
        }
        // secret 校验
        String secret = req.getHeader("X-App-Secret");
        if (secret == null || !secret.equals(str(g.get("app_secret")))) {
            return resp(HttpStatus.UNAUTHORIZED, "appSecret 不匹配");
        }
        // 状态
        if (!"ACTIVE".equals(str(g.get("status")))) return resp(HttpStatus.FORBIDDEN, "授权已停用");
        // 限时长（有效期）
        Object exp = g.get("expire_time");
        if (exp instanceof Timestamp) {
            if (System.currentTimeMillis() > ((Timestamp) exp).getTime()) return resp(HttpStatus.FORBIDDEN, "授权已过期");
        }
        // 限次 / 限流
        String deny = rateLimiter.check(appKey, lng(g.get("limit_count")), (int) lng(g.get("limit_qps")));
        if (deny != null) return resp(HttpStatus.TOO_MANY_REQUESTS, deny);

        // 平台代查（executor 自带 {param} 替换 + 资产状态校验 + 记日志）
        Map<String, Object> out = executor.invoke(str(g.get("service_code")), params, "app:" + str(g.get("grantee")), req.getRemoteAddr());
        out.put("app_key", appKey);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<Map<String, Object>> resp(HttpStatus s, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "FAIL");
        m.put("msg", msg);
        return ResponseEntity.status(s).body(m);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long lng(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }
}
