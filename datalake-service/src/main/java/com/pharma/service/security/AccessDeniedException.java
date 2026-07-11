package com.pharma.service.security;

/**
 * 三员分立越权异常，由全局处理器映射为 HTTP 403。
 * <p>
 * 双轨：旧构造 {@code (String msg)} 保留 22 处原调用零改动；新工厂 {@link #of(String, Object...)}
 * 走 i18n key 化，由 GlobalExceptionHandler 解析为当前 Locale 文案。
 */
public class AccessDeniedException extends RuntimeException {

    private final String key;
    private final Object[] args;

    /** 旧路径：直接传硬编码 message（key 留 null，处理器原样返回）。 */
    public AccessDeniedException(String msg) {
        super(msg);
        this.key = null;
        this.args = null;
    }

    /** i18n 路径私有构造：message 留空，仅持 key/args，交由处理器解析。 */
    private AccessDeniedException(String key, Object[] args) {
        super((String) null);
        this.key = key;
        this.args = args;
    }

    /**
     * 工厂：按 i18n key 构造异常，运行时由 GlobalExceptionHandler 解析为对应语言文案。
     *
     * @param key messages.properties 中的 key
     * @param args MessageFormat 占位符参数（{0} 等）
     */
    public static AccessDeniedException of(String key, Object... args) {
        return new AccessDeniedException(key, args);
    }

    public String getKey() {
        return key;
    }

    public Object[] getArgs() {
        return args;
    }
}
