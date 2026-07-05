package com.pharma.service.access.adapter;

import java.util.Map;

/**
 * 数据源连接描述（明文密码，由 Controller 层解密后填入，仅在内存传递）。
 */
public class DataSourceDescriptor {
    public long id;
    public String name;
    public String type;
    public String host;
    public int port;
    public String dbName;
    public String username;
    public String password;
    public String props;

    public static DataSourceDescriptor from(Map<String, Object> m) {
        DataSourceDescriptor d = new DataSourceDescriptor();
        d.id = lng(m.get("id"), 0);
        d.name = str(m.get("name"));
        d.type = str(m.get("type"));
        d.host = str(m.get("host"));
        d.port = (int) lng(m.get("port"), 0);
        d.dbName = str(m.get("db_name"));
        d.username = str(m.get("username"));
        d.password = str(m.get("password"));
        d.props = str(m.get("props"));
        return d;
    }

    public static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    public static long lng(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return def; }
    }
}
