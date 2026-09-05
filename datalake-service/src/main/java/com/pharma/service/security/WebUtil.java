package com.pharma.service.security;

import jakarta.servlet.http.HttpServletRequest;

/** Web 层小工具：客户端 IP 提取（AuthFilter 审计 / AuthController 登录日志共用）。 */
public final class WebUtil {

    private WebUtil() {}

    /** X-Forwarded-For 首段优先（经代理场景），否则远端地址。 */
    public static String clientIp(HttpServletRequest r) {
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return r.getRemoteAddr();
    }
}
