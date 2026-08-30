package com.flexforge.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI API Key 静态加密（docs/13 §3.6-5，S4 第二条受控通道）：AES-256-GCM，
 * 加密密钥经 HMAC-SHA256 从 {@code flexforge.auth.jwt-secret}（env
 * {@code AUTH_JWT_SECRET}，与 JWT 同源）以域分隔标签派生——密钥材料仍以环境
 * 变量为根，数据库泄露不直接泄露 API Key；secret 轮换后旧密文不可解
 * （decrypt 返回 null，调用方按"未配置"降级并提示重新录入）。密文格式
 * base64(iv[12] || ct)，GCM 认证标签随 ct 存放。明文只存在于调用栈内存，
 * 不进日志与错误消息（S8）。
 */
@Component
public final class SecretCipher {

    private static final byte[] DERIVATION_LABEL = "flexforge.ai-config.v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    private final SecureRandom random = new SecureRandom();

    /** 生产装配：与 JWT 同源的 secret（测试经 test yml 提供同属性）。 */
    public SecretCipher(@Value("${flexforge.auth.jwt-secret:}") String rootSecret) {
        if (rootSecret == null || rootSecret.isBlank()) {
            // 生产不可能到达（AuthProperties 启动 fail-fast）；此处不抛，保留到使用点给出可诊断错误
            this.aesKey = null;
            return;
        }
        this.aesKey = derive(rootSecret);
    }

    private static SecretKeySpec derive(String rootSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rootSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(DERIVATION_LABEL), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("加密密钥派生失败", e);
        }
    }

    /** 加密为 base64(iv||ct)；每次加密随机 IV（同明文两次密文不同）。 */
    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密；密文损坏或密钥不匹配（轮换/篡改/未派生）返回 null，由调用方降级处理。 */
    public String decrypt(String encoded) {
        if (aesKey == null) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= IV_BYTES) {
                return null;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            return null;
        }
    }

    private void requireKey() {
        if (aesKey == null) {
            throw new IllegalStateException("AUTH_JWT_SECRET 未配置，无法加密 API Key");
        }
    }
}
