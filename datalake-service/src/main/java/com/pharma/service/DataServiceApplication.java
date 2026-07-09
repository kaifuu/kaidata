package com.pharma.service;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据服务层启动类
 * <p>
 * 单体 Spring Boot 应用，通过 JDBC 查 StarRocks，为前端提供数据中台 REST API：
 *   - 数据中台全域：数据接入 / 治理 / 开发 / 资产 / 安全 / 服务 / 集市 / 运维 / 系统
 *     （查 meta.* 元数据库 + 动态多源 JDBC + SQL 工作台）
 * <p>
 * 运行在宿主机（JDK17），连接 StarRocks localhost:9030。
 */
@SpringBootApplication
@MapperScan("com.pharma.service.system.mapper")
@EnableScheduling   // 供 CaptchaStore 定时清扫过期验证码
public class DataServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataServiceApplication.class, args);
    }
}
