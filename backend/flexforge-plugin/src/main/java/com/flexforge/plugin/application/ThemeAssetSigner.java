package com.flexforge.plugin.application;

import com.flexforge.auth.AuthProperties;
import com.flexforge.common.PublicApi;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;

/**
 * 主题资产签名 URL（docs/09 P12.5，PR #32 审查 P1 修复）：CSS url()/裸 fetch
 * 无法携带 Bearer，聚合端点对每个资产下发短期 HMAC 签名 URL；serve 端点对
 * 匿名请求验签放行（登录请求仍走原 Bearer 路径）。
 * 密钥复用 AUTH_JWT_SECRET（域分隔前缀 theme-asset: 派生用途，S4：仅环境变量）；
 * 消息绑定 activationId + 资产路径 + 过期时刻，有效期与 JWT TTL 对齐。
 */
@PublicApi
@Component
public class ThemeAssetSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DOMAIN = "theme-asset:";

    private final byte[] key;
    private final Clock clock;
    private final long ttlSeconds;

    public ThemeAssetSigner(AuthProperties properties, Clock clock) {
        this.key = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.ttlSeconds = properties.getJwtTtl() == null
                ? 8 * 3600 : properties.getJwtTtl().toSeconds();
    }

    /** 资产路径为控制器 {*path} 形态（前导斜杠），签名与 URL 下发使用同一形态。 */
    public String sign(String activationId, String path, long expiresAtEpochSeconds) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return bytesToHex(mac.doFinal(message(activationId, path, expiresAtEpochSeconds)));
        } catch (Exception e) {
            throw new IllegalStateException("theme asset 签名失败", e);
        }
    }

    public long expiryFromNow() {
        return Instant.now(clock).getEpochSecond() + ttlSeconds;
    }

    /** 恒时比较校验（过期/篡改/路径不符一律 false）。 */
    public boolean verify(String activationId, String path, long expiresAtEpochSeconds, String sig) {
        if (sig == null || expiresAtEpochSeconds < Instant.now(clock).getEpochSecond()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(activationId, path, expiresAtEpochSeconds).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] message(String activationId, String path, long expiresAtEpochSeconds) {
        return (DOMAIN + activationId + "|" + path + "|" + expiresAtEpochSeconds)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
