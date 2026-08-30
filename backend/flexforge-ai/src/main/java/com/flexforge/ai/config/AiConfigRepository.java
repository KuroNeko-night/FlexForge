package com.flexforge.ai.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ai_provider_config 单行表访问（V012，FR-SETUP-01）：id 恒为 1（CHECK 兜底），
 * upsert 全量替换可回显字段；密文与掩码由服务层处理后传入（仓储不感知加密）。
 */
@Repository
public class AiConfigRepository {

    /** 存储行（api_key_cipher/api_key_hint 可空=未录入）。 */
    public record StoredAiConfig(String provider, String baseUrl, String model,
                                 String apiKeyCipher, String apiKeyHint) {
    }

    private final JdbcTemplate jdbc;

    public AiConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredAiConfig> find() {
        List<StoredAiConfig> rows = jdbc.query(
                "SELECT provider, base_url, model, api_key_cipher, api_key_hint "
                        + "FROM ai_provider_config WHERE id = 1",
                (rs, i) -> new StoredAiConfig(
                        rs.getString("provider"),
                        rs.getString("base_url"),
                        rs.getString("model"),
                        rs.getString("api_key_cipher"),
                        rs.getString("api_key_hint")));
        return rows.stream().findFirst();
    }

    public void upsert(StoredAiConfig config, String operator) {
        jdbc.update(
                "INSERT INTO ai_provider_config "
                        + "(id, provider, base_url, model, api_key_cipher, api_key_hint, "
                        + "updated_by, updated_at) VALUES (1, ?, ?, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET provider = EXCLUDED.provider, "
                        + "base_url = EXCLUDED.base_url, model = EXCLUDED.model, "
                        + "api_key_cipher = EXCLUDED.api_key_cipher, "
                        + "api_key_hint = EXCLUDED.api_key_hint, "
                        + "updated_by = EXCLUDED.updated_by, updated_at = now()",
                config.provider(), config.baseUrl(), config.model(),
                config.apiKeyCipher(), config.apiKeyHint(), operator);
    }
}
