package com.agentforge.session.impl.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 轻量 AES 工具 —— 仅用于保护落库 API Key（演示环境）。
 *
 * 生产建议: 密钥通过环境变量/配置中心注入，且使用随机 IV 而非固定 IV。
 */
@Slf4j
@Component
public class CryptoUtil {

    private static final String IV = "0000000000000000"; // 开发用固定 IV
    private final SecretKeySpec key;

    public CryptoUtil(@Value("${agentforge.encrypt-key:agentforge-dev-key}") String rawKey) {
        byte[] k = rawKey.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[16];
        System.arraycopy(k, 0, out, 0, Math.min(k.length, 16));
        this.key = new SecretKeySpec(out, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            return Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
            return new String(c.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }
}
