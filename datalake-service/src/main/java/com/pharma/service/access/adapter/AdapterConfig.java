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

    // ---- 登记型占位（大数据 / 消息 / 文件 / 缓存 / 对象存储）：经对应客户端/管道接入，此处仅登记 ----
    @Bean DataSourceAdapter kafkaAdapter() { return new PlaceholderAdapter("kafka", "", "kafka 为消息总线，本项目经「实时接入」Kafka 管道消费，数据源处仅做元数据登记。"); }
    @Bean DataSourceAdapter ftpAdapter() { return new PlaceholderAdapter("ftp", "", "FTP 文件协议请在「文件管理」注册文件存储后接入，数据源处仅做登记。"); }
    @Bean DataSourceAdapter sftpAdapter() { return new PlaceholderAdapter("sftp", "", "SFTP 文件协议请在「文件管理」注册文件存储后接入，数据源处仅做登记。"); }
    @Bean DataSourceAdapter sshAdapter() { return new PlaceholderAdapter("ssh", "", "SSH 远程文件通道，请在「文件管理」配置，数据源处仅做登记。"); }
    @Bean DataSourceAdapter redisAdapter() { return new PlaceholderAdapter("redis", "", "Redis 为内存缓存，暂未接入缓存管道，数据源处仅做登记。"); }
    @Bean DataSourceAdapter hdfsAdapter() { return new PlaceholderAdapter("hdfs", "", "HDFS 分布式存储，经大数据组件或「文件管理」接入，数据源处仅做登记。"); }
    @Bean DataSourceAdapter mongodbAdapter() { return new PlaceholderAdapter("mongodb", "", "MongoDB 文档库，需对应客户端驱动，数据源处仅做登记。"); }
    @Bean DataSourceAdapter hbaseAdapter() { return new PlaceholderAdapter("hbase", "", "HBase 列式库，需 ZK 地址配置，数据源处仅做登记。"); }
    @Bean DataSourceAdapter minioAdapter() { return new PlaceholderAdapter("minio", "", "MinIO 对象存储，本项目经「文件管理」MinIO 存储接入，数据源处仅做登记。"); }
}
