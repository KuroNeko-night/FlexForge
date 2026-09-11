package com.flexforge.ai.config;

import com.flexforge.common.audit.AuditEvent;
import com.flexforge.common.audit.AuditEventPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 运行时配置服务：合并优先级、校验失败路径、密钥语义（加密/掩码/保持/清除）与审计。 */
class AiConfigServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef";

    private final AiConfigRepository repository = mock(AiConfigRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final SecretCipher cipher = new SecretCipher(SECRET);
    private final ModelConfigGate gate = mock(ModelConfigGate.class);

    private AiConfigService service(AiEnv env) {
        return service(env, gate);
    }

    private AiConfigService service(AiEnv env, ModelConfigGate gate) {
        return new AiConfigService(repository, new AiConfigKernel(env, cipher, gate),
                audit, Clock.systemUTC());
    }

    private AiEnv env(String provider, String baseUrl, String model) {
        return new AiEnv(provider, baseUrl, model);
    }

    @Test
    void effectiveMergesStoredOverEnv() {
        when(repository.find()).thenReturn(Optional.of(new AiConfigRepository.StoredAiConfig(
                "http", "https://stored.example/v1", "stored-model",
                cipher.encrypt("sk-stored-key-987654"), "…7654")));
        AiConfigService.EffectiveModelConfig effective =
                service(env("fixture", "http://env.example", "env-model")).effective();
        assertThat(effective.provider()).isEqualTo("http");
        assertThat(effective.baseUrl()).isEqualTo("https://stored.example/v1");
        assertThat(effective.model()).isEqualTo("stored-model");
        assertThat(effective.apiKey()).isEqualTo("sk-stored-key-987654");
    }

    @Test
    void effectiveFallsBackToEnvThenFixture() {
        when(repository.find()).thenReturn(Optional.empty());
        AiConfigService.EffectiveModelConfig effective =
                service(env("", "", "")).effective();
        assertThat(effective.provider()).isEqualTo("fixture");
        assertThat(effective.model()).isEqualTo("gpt-4o-mini");
        assertThat(effective.apiKey()).isNull();
    }

    @Test
    void viewMasksKeyAndReportsStaleOnUndecryptableCipher() {
        when(repository.find()).thenReturn(Optional.of(new AiConfigRepository.StoredAiConfig(
                "http", "https://x.example", "m",
                new SecretCipher("another-secret-abcdef012345").encrypt("sk-key"),
                "…-key")));
        AiConfigService.ConfigView view = service(env("fixture", "", "")).view();
        assertThat(view.apiKeyConfigured()).isTrue();
        assertThat(view.apiKeyStale()).isTrue();
        assertThat(view.apiKeyHint()).isEqualTo("…-key");
    }

    @Test
    void updateRejectsUnknownProviderAndBadHttpShape() {
        AiConfigService svc = service(env("fixture", "", ""));
        assertThatThrownBy(() -> svc.update("op",
                new AiConfigService.UpdateCommand("openai", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fixture 或 http");
        assertThatThrownBy(() -> svc.update("op",
                new AiConfigService.UpdateCommand("http", null, "m", null, null)))
                .hasMessageContaining("base-url 必填");
        assertThatThrownBy(() -> svc.update("op",
                new AiConfigService.UpdateCommand("http", "ftp://x", "m", null, null)))
                .hasMessageContaining("http:// 或 https://");
        assertThatThrownBy(() -> svc.update("op",
                new AiConfigService.UpdateCommand("http", "https://x", " ", null, null)))
                .hasMessageContaining("model 必填");
        assertThatThrownBy(() -> svc.update("op",
                new AiConfigService.UpdateCommand("http", "https://x", "m", "k".repeat(5000), null)))
                .hasMessageContaining("超长");
    }

    @Test
    void updateEncryptsKeyWithTailMaskAndAudits() {
        ArgumentCaptor<AiConfigRepository.StoredAiConfig> saved =
                ArgumentCaptor.forClass(AiConfigRepository.StoredAiConfig.class);
        AiConfigService svc = service(env("fixture", "", ""));
        svc.update("admin-op", new AiConfigService.UpdateCommand(
                "http", "https://api.example/v1", "gpt-demo", "sk-plain-abcdef12", null));
        verify(repository).upsert(saved.capture(), org.mockito.ArgumentMatchers.eq("admin-op"));
        AiConfigRepository.StoredAiConfig row = saved.getValue();
        assertThat(row.provider()).isEqualTo("http");
        assertThat(row.apiKeyCipher()).isNotEqualTo("sk-plain-abcdef12");
        assertThat(cipher.decrypt(row.apiKeyCipher())).isEqualTo("sk-plain-abcdef12");
        assertThat(row.apiKeyHint()).isEqualTo("…ef12");
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void updateKeepsKeyWhenAbsentAndClearsOnFlag() {
        when(repository.find()).thenReturn(Optional.of(new AiConfigRepository.StoredAiConfig(
                "fixture", null, null, cipher.encrypt("sk-old"), "…old")));
        AiConfigService svc = service(env("fixture", "", ""));
        svc.update("op", new AiConfigService.UpdateCommand("fixture", null, null, null, null));
        ArgumentCaptor<AiConfigRepository.StoredAiConfig> kept =
                ArgumentCaptor.forClass(AiConfigRepository.StoredAiConfig.class);
        verify(repository).upsert(kept.capture(), org.mockito.ArgumentMatchers.eq("op"));
        assertThat(cipher.decrypt(kept.getValue().apiKeyCipher())).isEqualTo("sk-old");

        svc.update("op2", new AiConfigService.UpdateCommand("fixture", null, null, null, true));
        ArgumentCaptor<AiConfigRepository.StoredAiConfig> cleared =
                ArgumentCaptor.forClass(AiConfigRepository.StoredAiConfig.class);
        verify(repository).upsert(cleared.capture(), org.mockito.ArgumentMatchers.eq("op2"));
        assertThat(cleared.getValue().apiKeyCipher()).isNull();
        assertThat(cleared.getValue().apiKeyHint()).isNull();
    }

    @Test
    void updateHttpProbesWithEffectiveKeyBeforePersist() {
        AiConfigService svc = service(env("fixture", "", ""));
        svc.update("op", new AiConfigService.UpdateCommand(
                "http", "https://api.deepseek.com", "deepseek-flash", "sk-new-987654", null));
        org.mockito.Mockito.verify(gate).checkOnSave(
                "https://api.deepseek.com", "deepseek-flash", "sk-new-987654");
        verify(repository).upsert(any(AiConfigRepository.StoredAiConfig.class),
                org.mockito.ArgumentMatchers.eq("op"));
    }

    @Test
    void updateHttpProbesWithKeptKeyWhenNoNewKey() {
        when(repository.find()).thenReturn(Optional.of(new AiConfigRepository.StoredAiConfig(
                "http", "https://api.example", "m", cipher.encrypt("sk-kept-1234"), "…1234")));
        AiConfigService svc = service(env("fixture", "", ""));
        svc.update("op", new AiConfigService.UpdateCommand(
                "http", "https://api.deepseek.com", "deepseek-flash", null, null));
        org.mockito.Mockito.verify(gate).checkOnSave(
                "https://api.deepseek.com", "deepseek-flash", "sk-kept-1234");
    }

    @Test
    void updateHttpRejectsWhenNoKeyAtAll() {
        AiConfigService svc = service(env("fixture", "", ""));
        assertThatThrownBy(() -> svc.update("op", new AiConfigService.UpdateCommand(
                "http", "https://api.example", "m", " ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("API Key");
        org.mockito.Mockito.verifyNoInteractions(gate);
        verify(repository, org.mockito.Mockito.never()).upsert(
                any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateHttpProbeFailureRejectsPersistAndAuditsFailure() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("上游密钥无效（HTTP 401）"))
                .when(gate).checkOnSave("https://api.example", "m", "sk-x-987654");
        AiConfigService svc = service(env("fixture", "", ""));
        assertThatThrownBy(() -> svc.update("op", new AiConfigService.UpdateCommand(
                "http", "https://api.example", "m", "sk-x-987654", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("401");
        verify(repository, org.mockito.Mockito.never()).upsert(
                any(), org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().result()).isEqualTo("failure");
    }

    @Test
    void updateFixtureSkipsProbeGate() {
        AiConfigService svc = service(env("fixture", "", ""));
        svc.update("op", new AiConfigService.UpdateCommand("fixture", null, null, null, null));
        org.mockito.Mockito.verifyNoInteractions(gate);
        verify(repository).upsert(any(AiConfigRepository.StoredAiConfig.class),
                org.mockito.ArgumentMatchers.eq("op"));
    }
}
