package com.flexforge.ai.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 保存路径出站 URL 守卫（P26，docs/13 §3.6-6）：拒绝族全静态判定、无网络依赖。 */
class ModelUrlGuardTest {

    @Test
    void acceptsHttpAndHttpsPublicTargets() {
        assertThatCode(() -> ModelUrlGuard.requireFetchable("https://api.deepseek.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ModelUrlGuard.requireFetchable("http://api.example.com:8080/v1"))
                .doesNotThrowAnyException();
        // 非字面量主机名不做运行时 DNS——放行交探活报错
        assertThatCode(() -> ModelUrlGuard.requireFetchable("https://deepseek.internal.example"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsLocalhostAndLoopback() {
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://LOCALHOST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://127.0.0.1:9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("127.0.0.1");
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://127.8.8.8"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://[::1]/v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPrivateLinkLocalAndReservedRanges() {
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://10.1.2.3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://172.16.0.9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://192.168.1.4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://169.254.169.254"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://0.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://240.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://[fd12::1]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://[fe80::1]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadSchemeAndMalformedInput() {
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("ftp://api.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http://");
        // "https://" 在 URI 解析层属边缘形态（不同 JDK 或抛非法或 host 为空）——两种都是可诊断 400
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("https://"))
                .isInstanceOf(IllegalArgumentException.class);
        // 非法 IPv4 形态：URI 不产 host（registry-based authority）→ 主机名缺失或非法
        assertThatThrownBy(() -> ModelUrlGuard.requireFetchable("http://999.1.1.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机名");
    }
}
