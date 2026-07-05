package com.pharma.service.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 三员分立鉴权助手：基于令牌中的 roles 声明（逗号分隔的角色码）判定当前用户是否持有任一所需角色。
 * <p>
 * 三员模型：
 * <ul>
 *   <li>SYS_ADMIN 系统管理员 — 用户/组织/租户 生命周期</li>
 *   <li>SEC_ADMIN 安全保密管理员 — 角色/菜单/授权（含给用户授予角色）</li>
 *   <li>AUDIT_ADMIN 安全审计员 — 审计日志（只读）</li>
 * </ul>
 * 任一管理接口在入口处调用 {@link #require(String...)}；不满足抛 AccessDeniedException → 403。
 */
public final class Authz {

    public static final String SYS_ADMIN = "SYS_ADMIN";
    public static final String SEC_ADMIN = "SEC_ADMIN";
    public static final String AUDIT_ADMIN = "AUDIT_ADMIN";

    private Authz() {}

    /** 当前用户持有 required 中任一角色则放行，否则抛 403。 */
    public static void require(String... required) {
        if (!any(required)) {
            throw new AccessDeniedException("无权限：需要角色 " + String.join(" / ", required));
        }
    }

    /** 当前用户是否持有 required 中任一角色。 */
    public static boolean any(String... required) {
        Set<String> have = rolesOfCurrent();
        for (String r : required) if (have.contains(r)) return true;
        return false;
    }

    private static Set<String> rolesOfCurrent() {
        Object v = AuthContext.get().get("roles");
        if (v == null) return Set.of();
        String csv = v.toString().trim();
        if (csv.isEmpty()) return Set.of();
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }
}
