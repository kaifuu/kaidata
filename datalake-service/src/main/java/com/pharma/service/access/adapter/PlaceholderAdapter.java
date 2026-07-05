package com.pharma.service.access.adapter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 国产/闭源数据库占位适配器：驱动 jar 需手动放置，未放前仅做元数据登记与提示。
 * <p>
 * 适用类型：dameng(达梦) / kingbase(人大金仓) / gbase(南大通用)。
 * 待用户放置 jar 并在 pom 加 system scope 依赖后，新增对应 JdbcAdapter.of(...) 替换即可，
 * 元数据与已配置数据源无需迁移。
 */
public class PlaceholderAdapter implements DataSourceAdapter {

    private final String type;
    private final String jarHint;

    public PlaceholderAdapter(String type, String jarHint) {
        this.type = type;
        this.jarHint = jarHint;
    }

    @Override public String type() { return type; }
    @Override public boolean driverAvailable() { return false; }
    @Override public String jarHint() { return jarHint; }
    @Override public String driverClassName() { return ""; }
    @Override public String buildUrl(DataSourceDescriptor ds) { return ""; }

    @Override
    public Map<String, Object> testConnection(DataSourceDescriptor ds) {
        return Map.of("ok", false, "msg",
                type + " 驱动未就绪：请手动放置 " + jarHint + " 到 datalake-service/lib/，"
                        + "并在 pom.xml 加 <scope>system</scope> 依赖后重启服务。");
    }

    @Override public List<Map<String, Object>> listTables(DataSource pool, String schema) {
        throw new UnsupportedOperationException(type + " 驱动未就绪");
    }

    @Override public List<Map<String, Object>> describeTable(DataSource pool, String schema, String table) {
        throw new UnsupportedOperationException(type + " 驱动未就绪");
    }
}
