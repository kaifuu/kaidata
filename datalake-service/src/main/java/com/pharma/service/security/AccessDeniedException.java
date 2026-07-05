package com.pharma.service.security;

/**
 * 三员分立越权异常，由全局处理器映射为 HTTP 403。
 */
public class AccessDeniedException extends RuntimeException {
    public AccessDeniedException(String msg) { super(msg); }
}
