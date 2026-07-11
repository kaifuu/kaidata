package com.pharma.service.security;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 国际化消息工具：静态持有 MessageSource，供任意层（Controller / 异常处理器 / 鉴权助手）
 * 按 LocaleContextHolder 当前 Locale 取文案。找不到 key 时回退为 key 本身。
 */
@Component
public class I18nUtil implements MessageSourceAware {

    private static MessageSource ms;

    @Override
    public void setMessageSource(MessageSource messageSource) {
        I18nUtil.ms = messageSource;
    }

    public static String message(String key, Object... args) {
        return ms.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
