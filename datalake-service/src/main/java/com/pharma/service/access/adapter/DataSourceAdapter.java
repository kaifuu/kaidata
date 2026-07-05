package com.pharma.service.access.adapter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 数据源适配器 SPI：屏蔽不同数据库/存储的连接与元数据查询差异。
 * <ul>
 *   <li>{@link #driverAvailable()} 运行时探测驱动 jar 是否在 classpath（缺失时降级为占位）</li>
 *   <li>{@link #testConnection} 用临时连接探测，不占用注册中心连接池</li>
 *   <li>{@link #listTables}/>{@link #describeTable} 接收由 Registry 管理的连接池</li>
 * </ul>
 */
public interface DataSourceAdapter {

    /** 类型码，对应 meta.ing_datasource.type。 */
    String type();

    /** 驱动是否可用（classpath 探测）。 */
    boolean driverAvailable();

    /** 驱动未就绪时的提示文案（可空）。 */
    String jarHint();

    /** 探测连通，返回 {ok, latency?, product?, version?, msg?}。 */
    Map<String, Object> testConnection(DataSourceDescriptor ds);

    /** 构造 JDBC URL（或 REST 基址）。 */
    String buildUrl(DataSourceDescriptor ds);

    /** JDBC 驱动类名（REST 类型可返回空）。 */
    String driverClassName();

    /** 列源表：[{name, schema_name, comment}]。 */
    List<Map<String, Object>> listTables(DataSource pool, String schema);

    /** 描述表字段：[{name, type, comment}]。 */
    List<Map<String, Object>> describeTable(DataSource pool, String schema, String table);
}
