package com.pharma.service.access.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 可逆加密：用于数据源/文件存储密码。
 * <p>
 * 密钥由配置短语 {@code pharma.crypto.aes-key} 经 SHA-256 派生为 32 字节（AES-256），
 * 故配置值长度无约束。密文格式 = Base64( IV(12B) + ciphertext+tag )。
 * <p>
 * 演示用：生产应迁 KMS/Vault，并环境变量注入密钥。
 */
@Component
public class CryptoUtil {

    private final byte[] keyBytes;
    private final SecureRandom random = new SecureRandom();

    public CryptoUtil(@Value("${pharma.crypto.aes-key:pharma-datalake-2026-key}") String passphrase) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            this.keyBytes = md.digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("AES 密钥派生失败", e);
        }
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("加密失败", e);
        }
    }

    public String decrypt(String cipherB64) {
        if (cipherB64 == null || cipherB64.isEmpty()) return "";
        try {
            byte[] data = Base64.getDecoder().decode(cipherB64);
            if (data.length <= 12) return "";
            byte[] iv = new byte[12];
            byte[] ct = new byte[data.length - 12];
            System.arraycopy(data, 0, iv, 0, 12);
            System.arraycopy(data, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ""; // 兼容历史明文/异常密文
        }
    }
}
