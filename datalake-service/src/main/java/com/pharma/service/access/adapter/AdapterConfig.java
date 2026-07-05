package com.pharma.service.access.adapter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据源适配器装配：把所有类型（开源 JDBC + ES + 国产占位）注册为 Spring Bean，
 * 供 {@link DataSourceAdapterRegistry} 启动时扫描入按 type 索引的 Map。
 */
@Configuration
public class AdapterConfig {

    // ---- 开源 JDBC（真实连通，驱动缺失时自动降级） ----
    @Bean DataSourceAdapter mysqlAdapter() { return JdbcAdapter.of("mysql"); }
    @Bean DataSourceAdapter starrocksAdapter() { return JdbcAdapter.of("starrocks"); }
    @Bean DataSourceAdapter dorisAdapter() { return JdbcAdapter.of("doris"); }
    @Bean DataSourceAdapter postgresqlAdapter() { return JdbcAdapter.of("postgresql"); }
    @Bean DataSourceAdapter greenplumAdapter() { return JdbcAdapter.of("greenplum"); }
    @Bean DataSourceAdapter opengaussAdapter() { return JdbcAdapter.of("opengauss"); }
    @Bean DataSourceAdapter clickhouseAdapter() { return JdbcAdapter.of("clickhouse"); }
    @Bean DataSourceAdapter sqlserverAdapter() { return JdbcAdapter.of("sqlserver"); }
    @Bean DataSourceAdapter oracleAdapter() { return JdbcAdapter.of("oracle"); }
    @Bean DataSourceAdapter tdengineAdapter() { return JdbcAdapter.of("tdengine"); }
    @Bean DataSourceAdapter hiveAdapter() { return JdbcAdapter.of("hive"); }

    // ---- Elasticsearch（REST） ----
    @Bean DataSourceAdapter elasticsearchAdapter() { return new ElasticsearchAdapter(); }

    // ---- 国产占位（驱动需手动放置） ----
    @Bean DataSourceAdapter damengAdapter() { return new PlaceholderAdapter("dameng", "达梦 DmJdbcDriver18.jar"); }
    @Bean DataSourceAdapter kingbaseAdapter() { return new PlaceholderAdapter("kingbase", "人大金仓 kingbase8-8.6.0.jar"); }
    @Bean DataSourceAdapter gbaseAdapter() { return new PlaceholderAdapter("gbase", "南大通用 gbase-connector-java.jar"); }
}
