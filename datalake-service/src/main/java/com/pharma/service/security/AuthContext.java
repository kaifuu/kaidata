package com.pharma.service.security;

import java.util.Collections;
import java.util.Map;

/**
 * 当前请求的登录上下文（由 AuthFilter 注入，Controller/Service 读取）。
 * 基于 ThreadLocal，请求结束清理。
 */
public final class AuthContext {

    private static final ThreadLocal<Map<String, Object>> HOLDER = new ThreadLocal<>();

    public static void set(Map<String, Object> payload) { HOLDER.set(payload); }

    public static Map<String, Object> get() {
        Map<String, Object> p = HOLDER.get();
        return p == null ? Collections.emptyMap() : p;
    }

    public static String username() {
        Object u = get().get("username");
        return u == null ? "anonymous" : u.toString();
    }

    public static void clear() { HOLDER.remove(); }

    private AuthContext() {}
}
