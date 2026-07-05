package com.pharma.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据服务层启动类
 * <p>
 * 单体 Spring Boot 应用，通过 JDBC 查 StarRocks 数仓，为前端提供 REST API：
 *   - 环境监测（实时值/告警）→ 查 ods.ods_env_monitor / ods.ods_alarm
 *   - 批次质量追溯          → 查 ads.ads_batch_quality
 *   - 门户总览              → 各表计数聚合
 * <p>
 * 运行在宿主机（JDK17），连接 StarRocks localhost:9030。
 */
@SpringBootApplication
public class DataServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataServiceApplication.class, args);
    }
}
