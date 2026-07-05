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

    /** 拼接 base_path + path（去重复斜杠）。 */
    public static String resolve(Map<String, Object> m, String path) {
        String base = s(m, "base_path");
        String p = path == null ? "" : path;
        if (base.isEmpty()) return p.startsWith("/") ? p : "/" + p;
        if (p.isEmpty()) return base;
        return base.replaceAll("/+$", "") + "/" + p.replaceAll("^/+", "");
    }
}
