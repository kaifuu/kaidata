package com.pharma.service.system.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置：分页拦截器。
 * <p>
 * StarRocks 走 MySQL 协议 → DbType.MYSQL。其分页 SQL 为 {@code LIMIT offset, size}（逗号形式），
 * StarRocks 支持此形式（不支持 {@code LIMIT size OFFSET offset} 关键字形式），故兼容。
 * maxLimit=1000 限制单页上限，防止恶意大页拉爆。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor page = new PaginationInnerInterceptor(DbType.MYSQL);
        page.setMaxLimit(1000L);
        interceptor.addInnerInterceptor(page);
        return interceptor;
    }
}
