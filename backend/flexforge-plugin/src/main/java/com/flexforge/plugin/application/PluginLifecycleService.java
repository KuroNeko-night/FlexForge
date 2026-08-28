package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.common.registry.ExtensionPoints;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.ActivationStatus;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginManifest;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.PluginVersionRecord;
import com.flexforge.plugin.domain.StaleActivationException;
import com.flexforge.runtime.InMemoryExtensionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * PluginRuntime 生命周期（docs/07 §2-§5、docs/09 P08）：
 * 激活（迁移→注册→ACTIVE，失败回滚并记失败阶段）/ 停用（注册逆序释放+清理）/
 * 卸载（停用语义+审计保留）/ 升级失败 current 可用 / stale 拒绝 / 重启恢复。
 * 内存注册经 InMemoryExtensionRegistry（activationId 可撤销）；元数据经
 * service.meta 表写入并失效 MetaRegistry 缓存。
 */
@PublicApi
@Service
public class PluginLifecycleService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 协作者内核（参数上限口径）。 */
    record LifecycleKernel(LifecycleRepository lifecycle,
                           PluginPackageRepository packages,
                           MigrationScriptRunner scriptRunner,
                           MetaRegistry metaRegistry,
                           InMemoryExtensionRegistry extensions) {
    }

    private final LifecycleKernel kernel;
    private final AuditEventPort audit;
    private final Clock clock;

    public PluginLifecycleService(LifecycleKernel kernel,
                                  AuditEventPort audit, Clock clock) {
        this.kernel = kernel;
        this.audit = audit;
        this.clock = clock;
    }

    /** 激活（安装）指定版本：幂等（同 version 已 ACTIVE 返回既有 activationId）。 */
    public ActivationRecord activate(String actor, String versionId) {
        PluginVersionRecord version = findVersion(versionId);
        ActivationRecord existing = kernel.lifecycle().findActiveOperation(versionId, "ACTIVATE")
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        kernel.lifecycle().findOccupying(version.pluginId()).ifPresent(current -> {
            if (!current.pluginVersionId().equals(versionId)) {
                throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                        "插件已有进行中激活（" + current.id() + " @" + current.status().wireName()
                                + "），需先停用当前版本");
            }
        });

        ActivationRecord activation = kernel.lifecycle().insertActivation(
                "act-" + UUID.randomUUID(), version.pluginId(), versionId, "ACTIVATE", actor);
        try {
            runMigrations(activation, version);
            registerContributions(activation, version);
            kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.ACTIVE, "READY", null);
            kernel.metaRegistry().evictAll();
            audit.record(AuditEvents.of(actor, "plugin.activate", activation.id(), "success", clock));
            return requireActivation(activation.id());
        } catch (RuntimeException e) {
            rollback(activation, e);
            throw e;
        }
    }

    /** 停用：注册逆序释放 + plugin_registration 清理 + 实体 disabled。 */
    public ActivationRecord stop(String actor, String activationId) {
        ActivationRecord activation = requireActivation(activationId);
        if (activation.status() == ActivationStatus.STOPPED) {
            return activation;
        }
        requireActive(activation);
        kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.STOPPING, "STOPPING", null);
        kernel.extensions().closeAll(activationId);
        kernel.lifecycle().deleteRegistrations(activationId);
        kernel.lifecycle().deactivateEntity(activation.pluginId());
        kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.STOPPED, "STOPPED", null);
        kernel.metaRegistry().evictAll();
        kernel.lifecycle().appendPluginEvent(activation.pluginId(), activationId, "PLUGIN_STOPPED");
        audit.record(AuditEvents.of(actor, "plugin.stop", activationId, "success", clock));
        return requireActivation(activationId);
    }

    /** 卸载：停用语义 + 审计与 plugin_audit_event 保留。 */
    public void uninstall(String actor, String pluginId) {
        for (ActivationRecord active : kernel.lifecycle().findActiveByPlugin(pluginId)) {
            stop(actor, active.id());
        }
        kernel.lifecycle().appendPluginEvent(pluginId, null, "PLUGIN_UNINSTALLED");
        audit.record(AuditEvents.of(actor, "plugin.uninstall", pluginId, "success", clock));
    }

    /** 升级：先激活新版本，成功后停用旧版本；新版本失败旧版本保持可用。 */
    public ActivationRecord upgrade(String actor, String newVersionId) {
        PluginVersionRecord newVersion = findVersion(newVersionId);
        ActivationRecord activated = activate(actor, newVersionId);
        kernel.lifecycle().findActiveByPlugin(newVersion.pluginId()).stream()
                .filter(old -> !old.pluginVersionId().equals(newVersionId))
                .forEach(old -> stop(actor, old.id()));
        return activated;
    }

    /** 业务 API 前置校验（FR-PLUGIN-07）：仅当前 ACTIVE 激活可用。 */
    public void requireCurrentActivation(String activationId) {
        ActivationRecord activation = requireActivation(activationId);
        if (activation.status() != ActivationStatus.ACTIVE) {
            throw new StaleActivationException();
        }
        kernel.lifecycle().findOccupying(activation.pluginId())
                .filter(current -> current.id().equals(activationId))
                .orElseThrow(StaleActivationException::new);
    }

    /** 应用重启恢复：重建 ACTIVE 激活的内存注册（docs/07 §5-8）。 */
    public int restoreActivePlugins() {
        int restored = 0;
        for (ActivationRecord activation : kernel.lifecycle().findAllActive()) {
            try {
                registerInMemory(activation.id(), findVersion(activation.pluginVersionId()));
                restored++;
            } catch (RuntimeException e) {
                kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.FAILED,
                        "RESTORE", ErrorCodes.REGISTRATION_FAILED);
            }
        }
        return restored;
    }

    /** 迁移执行层（ADR-0005：checksum 一致跳过；差异拒绝；脚本执行经 MigrationScriptRunner）。 */
    private void runMigrations(ActivationRecord activation, PluginVersionRecord version) {
        Map<String, String> applied = new HashMap<>();
        for (LifecycleRepository.MigrationEntry entry : kernel.lifecycle().migrationsOf(version.id())) {
            applied.put(entry.scriptName(), entry.checksum());
        }
        for (String scriptName : version.scriptChecksums().keySet()) {
            String expectedChecksum = version.scriptChecksums().get(scriptName);
            String previous = applied.get(scriptName);
            if (previous != null && !previous.equals(expectedChecksum)) {
                throw new PluginValidationException(ErrorCodes.MIGRATION_FAILED,
                        "迁移脚本 checksum 与已应用记录不一致（包损坏拒绝）: " + scriptName);
            }
            if (previous == null) {
                kernel.scriptRunner().executeFromVersion(scriptName, version.id());
                kernel.lifecycle().insertMigration(version.id(), activation.id(),
                        scriptName, expectedChecksum);
            }
        }
    }

    /** 注册编排：metadata 实体（service.meta）+ navigation/renderer（ExtensionRegistry）。 */
    private void registerContributions(ActivationRecord activation, PluginVersionRecord version) {
        try {
            registerMetadata(activation, version);
            registerInMemory(activation.id(), version);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new PluginValidationException(ErrorCodes.REGISTRATION_FAILED,
                    "注册冲突（贡献键已被占用）: " + e.getMessage());
        }
    }

    private void registerMetadata(ActivationRecord activation, PluginVersionRecord version) {
        String entitiesJson = kernel.lifecycle().resourcePayloadsOf(version.id());
        JsonNode entities = entitiesJson == null ? JSON.readTree("{}")
                : JSON.readTree(entitiesJson.getBytes(StandardCharsets.UTF_8));
        for (String path : entities.propertyNames()) {
            JsonNode entitySpec = entities.get(path);
            String entityName = entitySpec.get("name").asText();
            kernel.lifecycle().insertEntityWithFields(entityName,
                    entitySpec.path("displayName").asString(entityName),
                    version.pluginId(), fieldSpecsOf(entitySpec));
            kernel.lifecycle().insertRegistration(activation.id(), "service.meta", entityName,
                    entitySpec.toString());
        }
        for (String navigationKey : navigationKeysOf(version)) {
            kernel.lifecycle().insertRegistration(activation.id(), ExtensionPoints.NAVIGATION,
                    navigationKey, "{\"key\":\"" + navigationKey + "\"}");
        }
        for (String rendererId : rendererIdsOf(version)) {
            kernel.lifecycle().insertRegistration(activation.id(), ExtensionPoints.FIELD_RENDERER,
                    rendererId, "{\"rendererId\":\"" + rendererId + "\"}");
        }
    }

    private List<LifecycleRepository.FieldSpec> fieldSpecsOf(JsonNode entitySpec) {
        JsonNode fields = entitySpec.path("fields");
        List<LifecycleRepository.FieldSpec> result = new java.util.ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            JsonNode field = fields.get(i);
            result.add(new LifecycleRepository.FieldSpec(
                    field.get("name").asText(),
                    field.path("displayName").asString(field.get("name").asText()),
                    field.get("fieldType").asText(),
                    field.path("required").asBoolean(false),
                    field.has("validation") ? field.get("validation").toString() : null,
                    field.has("defaultValue") ? field.get("defaultValue").toString() : null,
                    field.path("position").asInt(0)));
        }
        return result;
    }

    private List<String> navigationKeysOf(PluginVersionRecord version) {
        return manifestContributions(version).getOrDefault("navigation", List.of());
    }

    private List<String> rendererIdsOf(PluginVersionRecord version) {
        return manifestContributions(version).getOrDefault("renderers", List.of());
    }

    private Map<String, List<String>> manifestContributions(PluginVersionRecord version) {
        JsonNode manifest = JSON.readTree(version.manifestJson());
        JsonNode contributions = manifest.get("contributions");
        if (contributions == null || !contributions.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> result = new HashMap<>();
        for (String key : contributions.propertyNames()) {
            JsonNode array = contributions.get(key);
            if (array != null && array.isArray()) {
                List<String> values = new java.util.ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    values.add(array.get(i).asText());
                }
                result.put(key, List.copyOf(values));
            }
        }
        return result;
    }

    /** 内存注册（激活与重启恢复共用）：navigation 进 ExtensionRegistry。 */
    private void registerInMemory(String activationId, PluginVersionRecord version) {
        for (String navigationKey : navigationKeysOf(version)) {
            kernel.extensions().register(ExtensionPoints.NAVIGATION,
                    Map.of("key", navigationKey, "title", displayNameOf(version), "order", 100),
                    activationId);
        }
    }

    private static String displayNameOf(PluginVersionRecord version) {
        JsonNode manifest = JSON.readTree(version.manifestJson());
        JsonNode name = manifest.get("name");
        return name == null ? version.pluginId() : name.asText();
    }

    private void rollback(ActivationRecord activation, RuntimeException error) {
        kernel.extensions().closeAll(activation.id());
        kernel.lifecycle().deleteRegistrations(activation.id());
        kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.FAILED,
                errorStage(error), errorCode(error));
        kernel.lifecycle().appendPluginEvent(activation.pluginId(), activation.id(), "ACTIVATION_FAILED");
    }

    private static String errorStage(RuntimeException error) {
        if (error instanceof PluginValidationException pve) {
            if (ErrorCodes.MIGRATION_FAILED.equals(pve.code())) {
                return "MIGRATION";
            }
            if (ErrorCodes.REGISTRATION_FAILED.equals(pve.code())) {
                return "REGISTER";
            }
        }
        return "DEPENDENCY_CHECK";
    }

    private static String errorCode(RuntimeException error) {
        return error instanceof PluginValidationException pve ? pve.code()
                : ErrorCodes.INTERNAL_ERROR;
    }

    private PluginVersionRecord findVersion(String versionId) {
        return kernel.packages().findByVersionId(versionId)
                .orElseThrow(() -> new NoSuchElementException("插件版本不存在: " + versionId));
    }

    private ActivationRecord requireActivation(String activationId) {
        return kernel.lifecycle().findActivation(activationId)
                .orElseThrow(() -> new NoSuchElementException("激活不存在: " + activationId));
    }

    private static void requireActive(ActivationRecord activation) {
        if (activation.status() != ActivationStatus.ACTIVE) {
            throw new StaleActivationException();
        }
    }
}
