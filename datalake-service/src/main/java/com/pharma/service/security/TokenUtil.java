package com.pharma.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 签名令牌（JWT 结构：header.payload.signature）
 * <p>
 * 无外部依赖（jjwt 等本机拉不到，故自实现）。载荷含 username/name/role/exp。
 * 签名密钥从配置 {@code pharma.security.token-sign-key} 读取（环境变量 PHARMA_TOKEN_KEY 优先，
 * 默认值兼容存量 token），不再硬编码于源码。过期默认 12h。
 */
@Component
public class TokenUtil {

    private final String secret;
    private static final long TTL_SECONDS = 12 * 3600L;
    private static final ObjectMapper M = new ObjectMapper();

    public TokenUtil(@Value("${pharma.security.token-sign-key:pharma-datalake-sign-secret-2026}") String secret) {
        this.secret = secret;
    }

    public String issue(String username, String name, String role) {
        return issue(username, name, role, role);
    }

    /**
     * 签发令牌（携带全部角色码 roles，供三员分立鉴权使用）。
     *
     * @param role     主角色（兼容旧字段）
     * @param rolesCsv 该用户全部角色码，逗号分隔（如 "SYS_ADMIN,SEC_ADMIN"）
     */
    public String issue(String username, String name, String role, String rolesCsv) {
        try {
            String header = b64(M.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT")));
            long exp = System.currentTimeMillis() / 1000 + TTL_SECONDS;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("name", name);
            body.put("role", role);
            body.put("roles", rolesCsv == null ? role : rolesCsv);
            body.put("exp", exp);
            String payload = b64(M.writeValueAsString(body));
            String signingInput = header + "." + payload;
            return signingInput + "." + b64(hmac(signingInput));
        } catch (Exception e) {
            throw new RuntimeException("签发令牌失败", e);
        }
    }

    /**
     * 校验签名与有效期，成功返回载荷 Map，失败返回 null。
     * 兼容完整三段式（header.payload.sig）与两段式（payload.sig）；
     * HMAC 始终覆盖"最后一段之前的全部内容"。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify(String raw) {
        try {
            if (raw == null) return null;
            if (raw.startsWith("Bearer ")) raw = raw.substring(7);
            String[] p = raw.split("\\.");
            if (p.length != 3 && p.length != 2) return null;
            String payload = p[p.length - 2];
            String sig = p[p.length - 1];
            String signingInput = (p.length == 3) ? p[0] + "." + p[1] : p[0];
            if (!safeEquals(hmac(signingInput), dec(sig))) return null;
            Map<String, Object> body = M.readValue(dec(payload), Map.class);
            Number exp = (Number) body.get("exp");
            if (exp != null && System.currentTimeMillis() / 1000 > exp.longValue()) return null;
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String b64(String s) { return b64(s.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] dec(String s) { return Base64.getUrlDecoder().decode(s); }
    private static boolean safeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    public static final Map<String, Object> EMPTY = Collections.emptyMap();
}
