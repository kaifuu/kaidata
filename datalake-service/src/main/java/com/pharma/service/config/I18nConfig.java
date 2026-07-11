package com.pharma.service.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

/**
 * 国际化基座：
 * <ul>
 *   <li>messageSource —— 加载 classpath 根的 messages*.properties（UTF-8），关闭系统 locale 回退。</li>
 *   <li>localeResolver —— 按 Accept-Language 请求头解析 Locale，默认中文、兜底英文。</li>
 * </ul>
 */
@Configuration
public class I18nConfig {

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasenames("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        // 默认中文；无 Accept-Language 头时回退到此。MessageSource 侧 fallbackToSystemLocale(false)
        // 保证找不到匹配 properties 时不再回退到 JVM 系统 locale。
        resolver.setDefaultLocale(Locale.CHINESE);
        return resolver;
    }
}
