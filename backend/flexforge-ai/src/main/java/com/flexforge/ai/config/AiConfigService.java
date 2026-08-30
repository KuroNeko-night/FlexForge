package com.flexforge.ai.config;

import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * AI 模型运行时配置（FR-SETUP-01，docs/13 §3.6-5）：读取合并（DB 行 &gt; 环境缺省
 * &gt; fixture 默认）与管理员更新（校验+加密+审计）。密钥明文只存在于加密前调用栈
 * 与模型请求头；任何读路径只回"已配置"位与尾 4 位掩码（S4/S8）。
 * 并发口径：update 为单行全量替换（最后写胜）——MVP 单管理员场景，
 * 需字段级合并时再引入版本号（PR #35 审查 P3 登记）。
 */
@PublicApi
@Service
public class AiConfigService {

    private static final Set<String> PROVIDERS = Set.of("fixture", "http");

    /** 模型调用有效配置（RoutingModelPort/HttpModelPort 每次调用时解析）。 */
    @PublicApi
    public record EffectiveModelConfig(String provider, String baseUrl, String model,
                                       String apiKey) {
    }

    /** 读视图（GET /ai/config）：不含密钥明文。 */
    @PublicApi
    public record ConfigView(String provider, String baseUrl, String model,
                             boolean apiKeyConfigured, String apiKeyHint, boolean apiKeyStale) {
    }

    /** 更新命令：apiKey 为 null/空白=保持不变；clearApiKey=true=清除。 */
    public record UpdateCommand(String provider, String baseUrl, String model,
                                String apiKey, Boolean clearApiKey) {
    }

    private final AiConfigRepository repository;
    private final AiEnv env;
    private final AuditEventPort audit;
    private final Clock clock;
    private final SecretCipher cipher;

    public AiConfigService(AiConfigRepository repository, AiEnv env,
                           AuditEventPort audit, Clock clock, SecretCipher cipher) {
        this.repository = repository;
        this.env = env;
        this.audit = audit;
        this.clock = clock;
        this.cipher = cipher;
    }

    public EffectiveModelConfig effective() {
        Optional<AiConfigRepository.StoredAiConfig> stored = repository.find();
        String provider = stored.map(AiConfigRepository.StoredAiConfig::provider)
                .filter(p -> !p.isBlank()).orElseGet(env::provider);
        String baseUrl = stored.map(AiConfigRepository.StoredAiConfig::baseUrl)
                .filter(u -> u != null && !u.isBlank()).orElseGet(env::baseUrl);
        String model = stored.map(AiConfigRepository.StoredAiConfig::model)
                .filter(m -> m != null && !m.isBlank()).orElseGet(env::model);
        String apiKey = stored.map(AiConfigRepository.StoredAiConfig::apiKeyCipher)
                .filter(c -> c != null && !c.isBlank())
                .map(cipher::decrypt)
                .filter(k -> k != null && !k.isBlank())
                .orElseGet(env::envApiKey);
        return new EffectiveModelConfig(provider, baseUrl, model, apiKey);
    }

    public ConfigView view() {
        Optional<AiConfigRepository.StoredAiConfig> storedOpt = repository.find();
        EffectiveModelConfig effective = effective();
        AiConfigRepository.StoredAiConfig stored = storedOpt.orElse(null);
        boolean configured = stored != null && stored.apiKeyCipher() != null
                && !stored.apiKeyCipher().isBlank();
        // 密文存在但不可解（AUTH_JWT_SECRET 轮换/篡改）→ 提示重新录入（docs/13 §3.6-5）
        boolean stale = configured && cipher.decrypt(stored.apiKeyCipher()) == null;
        return new ConfigView(effective.provider(), effective.baseUrl(), effective.model(),
                configured, stored == null ? null : stored.apiKeyHint(), stale);
    }

    public void update(String operator, UpdateCommand command) {
        String provider = requireProvider(command.provider());
        String baseUrl = normalize(command.baseUrl(), 500);
        String model = normalize(command.model(), 100);
        requireHttpShape(provider, baseUrl, model);

        AiConfigRepository.StoredAiConfig current = repository.find().orElse(null);
        KeyMaterial key = resolveKey(command, current);
        AiConfigRepository.StoredAiConfig next = new AiConfigRepository.StoredAiConfig(
                provider, baseUrl, model, key.cipher(), key.hint());
        repository.upsert(next, operator);
        audit.record(AuditEvents.of(operator, "ai.config", "ai-provider-config",
                "success", clock));
    }

    private static String requireProvider(String provider) {
        if (provider == null || !PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("provider 必须是 fixture 或 http");
        }
        return provider;
    }

    /** http 形态约束：base-url 须为 http(s) URL 且非空、model 非空（可诊断 400）。 */
    private static void requireHttpShape(String provider, String baseUrl, String model) {
        if (!"http".equals(provider)) {
            return;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("provider=http 时 base-url 必填");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("base-url 必须以 http:// 或 https:// 开头");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("provider=http 时 model 必填");
        }
    }

    private static String normalize(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("字段超长（上限 " + max + " 字符）");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record KeyMaterial(String cipher, String hint) {
    }

    /** 密钥语义：clearApiKey=true 清除；非空白=重设（加密+尾 4 位掩码）；否则保持。 */
    private KeyMaterial resolveKey(UpdateCommand command,
                                   AiConfigRepository.StoredAiConfig current) {
        if (Boolean.TRUE.equals(command.clearApiKey())) {
            return new KeyMaterial(null, null);
        }
        String raw = command.apiKey();
        if (raw == null || raw.strip().isEmpty()) {
            return new KeyMaterial(current == null ? null : current.apiKeyCipher(),
                    current == null ? null : current.apiKeyHint());
        }
        if (raw.length() > 4096) {
            throw new IllegalArgumentException("API Key 超长（上限 4096 字符）");
        }
        String trimmed = raw.strip();
        return new KeyMaterial(cipher.encrypt(trimmed), maskOf(trimmed));
    }

    private static String maskOf(String key) {
        return key.length() >= 8 ? "…" + key.substring(key.length() - 4) : "…****";
    }
}
