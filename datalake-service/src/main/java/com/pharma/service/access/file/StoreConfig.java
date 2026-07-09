package com.pharma.service.access.file;

import java.util.Map;

/** 从 store Map 提取连接配置的助手。 */
public final class StoreConfig {
    private StoreConfig() {}

    public static String s(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v); }
    public static int port(Map<String, Object> m, int def) {
        Object v = m.get("port");
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return def; }
    }

    /**
     * 解析为相对登录根的路径（去首尾斜杠）。
     * <p>约定：服务器已通过 chroot / local_root 把用户锁定到 base_path，
     * 故 base_path 仅作前端展示，不参与路径拼接；客户端用相对路径访问登录根下的子项。
     */
    public static String resolve(Map<String, Object> m, String path) {
        String p = path == null ? "" : path;
        return p.replaceAll("^/+", "").replaceAll("/+$", "");
    }
}
