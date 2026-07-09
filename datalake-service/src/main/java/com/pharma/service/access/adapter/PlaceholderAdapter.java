package com.pharma.service.access.adapter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 占位适配器：驱动 jar 需手动放置，或为登记型（非 JDBC）类型 —— 仅做元数据登记与提示，不真正连通。
 * <p>
 * 适用：国产库 dameng/kingbase/gbase（放 jar 后可用）；登记型 kafka/ftp/sftp/ssh/redis/hdfs/mongodb/hbase/minio
 * （经对应客户端/管道接入，数据源处仅登记）。
 * <p>
 * 待对应驱动/客户端就绪后，新增 JdbcAdapter.of(...) 或专用适配器替换即可，元数据与已配置数据源无需迁移。
 */
public class PlaceholderAdapter implements DataSourceAdapter {

    private final String type;
    private final String jarHint;
    private final String testMessage;   // 可空：非 JDBC 登记型类型的测试提示文案

    public PlaceholderAdapter(String type, String jarHint) {
        this(type, jarHint, null);
    }

    /** 登记型类型：testMessage 为测试连接时返回的提示文案。 */
    public PlaceholderAdapter(String type, String jarHint, String testMessage) {
        this.type = type;
        this.jarHint = jarHint;
        this.testMessage = testMessage;
    }

    @Override public String type() { return type; }
    @Override public boolean driverAvailable() { return false; }
    @Override public String jarHint() { return jarHint; }
    @Override public String driverClassName() { return ""; }
    @Override public String buildUrl(DataSourceDescriptor ds) { return ""; }

    @Override
    public Map<String, Object> testConnection(DataSourceDescriptor ds) {
        String msg = testMessage != null ? testMessage
                : type + " 驱动未就绪：请手动放置 " + jarHint + " 到 datalake-service/lib/，"
                        + "并在 pom.xml 加 <scope>system</scope> 依赖后重启服务。";
        return Map.of("ok", false, "msg", msg);
    }

    @Override public List<Map<String, Object>> listTables(DataSource pool, String schema) {
        throw new UnsupportedOperationException(type + " 为登记型/驱动未就绪");
    }

    @Override public List<Map<String, Object>> describeTable(DataSource pool, String schema, String table) {
        throw new UnsupportedOperationException(type + " 为登记型/驱动未就绪");
    }
}
