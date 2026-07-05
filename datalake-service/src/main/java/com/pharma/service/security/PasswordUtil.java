package com.pharma.service.security;

/**
 * 密码哈希（SHA-256 + 固定盐前缀）。
 * 演示用：生产应换 BCrypt/argon2（StarRocks 不算哈希，故 Java 侧算）。
 */
public final class PasswordUtil {

    private static final String SALT = "pharma$2026.";

    public static String hash(String raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SALT + raw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean matches(String raw, String hashed) {
        return hash(raw).equals(hashed);
    }

    private PasswordUtil() {}
}
