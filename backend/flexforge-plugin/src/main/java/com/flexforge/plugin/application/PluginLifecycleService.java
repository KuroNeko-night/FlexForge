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
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.PluginVersionRecord;
import com.flexforge.plugin.domain.StaleActivationException;
import com.flexforge.plugin.domain.ThemeAssetSpec;
import com.flexforge.runtime.InMemoryExtensionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * PluginRuntime 生命周期（docs/07 §2-§5、docs/09 P08）：
 * 激活（依赖检查→事务内迁移+注册→ACTIVE，失败回滚并记失败阶段）/ 停用（注册逆序
 * 释放+清理）/ 卸载（停用语义+审计保留）/ 升级（停旧→激活新，失败补偿重激活旧版
 * 保持 current 可用）/ stale 拒绝 / 重启恢复。内存注册经 InMemoryExtensionRegistry
 * （activationId 可撤销）；元数据经 service.meta 表写入并失效 MetaRegistry 缓存。
 * 迁移与注册在单一事务内提交（ADR-0005）；失败状态写在事务外，确保 FAILED 记录留存。
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
    private final TransactionTemplate transactions;

    public PluginLifecycleService(LifecycleKernel kernel, AuditEventPort audit, Clock clock,
                                  TransactionTemplate transactions) {
        this.kernel = kernel;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    /** 激活（安装）指定版本：幂等（同 version 已 ACTIVE 返回既有 activationId）。 */
    public ActivationRecord activate(String actor, String versionId) {
        PluginVersionRecord version = findVersion(versionId);
        ActivationRecord existing = kernel.lifecycle().findActiveOperation(versionId, "ACTIVATE")
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        requireSlotFree(version.pluginId(), versionId);

        ActivationRecord activation;
        try {
            activation = kernel.lifecycle().insertActivation(
                    "act-" + UUID.randomUUID(), version.pluginId(), versionId, "ACTIVATE", actor);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // V009 部分唯一索引兜底并发窗口（Issue #22-1）：败者复查幂等口径
            return kernel.lifecycle().findActiveOperation(versionId, "ACTIVATE")
                    .orElseThrow(() -> slotOccupied("并发冲突败者"));
        }
        try {
            checkDependencies(version);
            transactions.executeWithoutResult(tx -> {
                runMigrations(activation, version);
                registerContributions(activation, version);
                kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.ACTIVE,
                        "READY", null);
            });
        } catch (RuntimeException e) {
            rollback(activation, e);
            throw e;
        }
        kernel.metaRegistry().evictAll();
        audit.record(AuditEvents.of(actor, "plugin.activate", activation.id(), "success", clock));
        return requireActivation(activation.id());
    }

    private void requireSlotFree(String pluginId, String versionId) {
        kernel.lifecycle().findOccupying(pluginId).ifPresent(current -> {
            if (!current.pluginVersionId().equals(versionId)) {
                throw slotOccupied(current.id() + " @" + current.status().wireName());
            }
        });
    }

    private static PluginValidationException slotOccupied(String detail) {
        return new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                "插件已有进行中激活（" + detail + "），需先停用当前版本");
    }

    /** 停用：DB 清理（注册/实体/状态）单事务提交，事务外撤销内存注册。 */
    public ActivationRecord stop(String actor, String activationId) {
        ActivationRecord activation = requireActivation(activationId);
        if (activation.status() == ActivationStatus.STOPPED) {
            return activation;
        }
        requireActive(activation);
        kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.STOPPING, "STOPPING",
                null);
        transactions.executeWithoutResult(tx -> {
            kernel.lifecycle().deleteRegistrations(activationId);
            kernel.lifecycle().deactivateEntity(activation.pluginId());
            kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.STOPPED, "STOPPED",
                    null);
        });
        kernel.extensions().closeAll(activationId);
        kernel.metaRegistry().evictAll();
        kernel.lifecycle().appendPluginEvent(activation.pluginId(), activationId,
                "PLUGIN_STOPPED");
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

    /**
     * 升级：停旧→激活新（避免占用检查冲突）；新版本失败时补偿性重激活旧版本，
     * current 保持可用（docs/07 §5-6、docs/09 P08 验收）。非原子切换，停旧到新激活
     * 成功间存在短暂不可用窗口；补偿失败记 UPGRADE_ROLLBACK_FAILED 事件并抛原始失败。
     */
    public ActivationRecord upgrade(String actor, String newVersionId) {
        PluginVersionRecord newVersion = findVersion(newVersionId);
        List<ActivationRecord> currents = kernel.lifecycle()
                .findActiveByPlugin(newVersion.pluginId()).stream()
                .filter(old -> !old.pluginVersionId().equals(newVersionId)).toList();
        currents.forEach(old -> stop(actor, old.id()));
        try {
            return activate(actor, newVersionId);
        } catch (RuntimeException failure) {
            restoreCurrentVersions(actor, currents);
            throw failure;
        }
    }

    /** 业务 API 前置校验（FR-PLUGIN-07）：仅当前 ACTIVE 激活可用；返回该激活记录。 */
    public ActivationRecord requireCurrentActivation(String activationId) {
        ActivationRecord activation = requireActivation(activationId);
        if (activation.status() != ActivationStatus.ACTIVE) {
            throw new StaleActivationException();
        }
        kernel.lifecycle().findOccupying(activation.pluginId())
                .filter(current -> current.id().equals(activationId))
                .orElseThrow(StaleActivationException::new);
        return activation;
    }

    /** 激活注册清单查询（stale 校验后的只读视图）。 */
    public List<LifecycleRepository.RegistrationEntry> registrationsOf(String activationId) {
        requireCurrentActivation(activationId);
        return kernel.lifecycle().registrationsOf(activationId);
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

    /** DEPENDENCY_CHECK（docs/07 §4-§5-2）：依赖插件需存在满足版本范围的 ACTIVE 激活。 */
    private void checkDependencies(PluginVersionRecord version) {
        JsonNode dependencies = JSON.readTree(version.manifestJson()).path("dependencies");
        for (int i = 0; i < dependencies.size(); i++) {
            JsonNode dependency = dependencies.get(i);
            String pluginId = dependency.path("pluginId").asString();
            String range = dependency.path("versionRange").asString("*");
            kernel.lifecycle().findActiveByPlugin(pluginId).stream()
                    .filter(active -> DependencyResolver.satisfies(
                            findVersion(active.pluginVersionId()).version(), range))
                    .findAny()
                    .orElseThrow(() -> new PluginValidationException(ErrorCodes.DEPENDENCY_MISSING,
                            "依赖插件未激活: " + pluginId + " 需满足 " + range
                                    + "（仅导入未安装不满足激活条件）"));
        }
    }

    /** 迁移执行层（ADR-0005：声明顺序执行、checksum 一致跳过、差异拒绝）。 */
    private void runMigrations(ActivationRecord activation, PluginVersionRecord version) {
        Map<String, String> applied = new HashMap<>();
        for (LifecycleRepository.MigrationEntry entry : kernel.lifecycle().migrationsOf(
                version.id())) {
            applied.put(entry.scriptName(), entry.checksum());
        }
        for (String scriptName : PluginContributionFactory.migrationOrderOf(version)) {
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
        JsonNode payloads = PluginContributionFactory.parseEntities(
                kernel.lifecycle().resourcePayloadsOf(version.id()));
        registerEntities(activation, version, payloads);
        registerViews(payloads);
        for (String navigationKey : PluginContributionFactory.navigationKeysOf(version)) {
            kernel.lifecycle().insertRegistration(activation.id(), ExtensionPoints.NAVIGATION,
                    navigationKey, PluginContributionFactory.navigationPayload(navigationKey,
                            version, payloads));
        }
        for (String rendererId : PluginContributionFactory.rendererIdsOf(version)) {
            kernel.lifecycle().insertRegistration(activation.id(), ExtensionPoints.FIELD_RENDERER,
                    rendererId, "{\"rendererId\":\"" + rendererId + "\"}");
        }
        for (ThemeAssetSpec asset : PluginContributionFactory.themeAssetsOf(version)) {
            kernel.lifecycle().insertRegistration(activation.id(), ExtensionPoints.THEME_ASSET,
                    asset.key(), PluginContributionFactory.themeAssetPayload(asset));
        }
    }

    private void registerEntities(ActivationRecord activation, PluginVersionRecord version,
                                  JsonNode payloads) {
        for (String path : payloads.propertyNames()) {
            if (!PluginContributionFactory.isEntityPath(path)) {
                continue;
            }
            JsonNode entitySpec = PluginContributionFactory.entitySpec(payloads, path);
            String entityName = entitySpec.get("name").asString();
            requireEntityOwnership(entityName, version.pluginId());
            kernel.lifecycle().insertEntityWithFields(entityName,
                    entitySpec.path("displayName").asString(entityName),
                    version.pluginId(), PluginContributionFactory.fieldSpecsOf(entitySpec));
            kernel.lifecycle().insertRegistration(activation.id(), "service.meta", entityName,
                    entitySpec.toString());
        }
    }

    /** 视图注册（metadata/views/*）：viewType 限 list/form，实体必须为本包注册实体。 */
    private void registerViews(JsonNode payloads) {
        Set<String> entityNames = new HashSet<>();
        for (String path : payloads.propertyNames()) {
            if (PluginContributionFactory.isEntityPath(path)) {
                entityNames.add(PluginContributionFactory.entitySpec(payloads, path)
                        .path("name").asString());
            }
        }
        for (JsonNode view : PluginContributionFactory.viewSpecsOf(payloads)) {
            String viewType = view.path("viewType").asString();
            String entityName = view.path("entity").asString();
            if (!List.of("list", "form").contains(viewType)) {
                throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                        "视图 viewType 非法（允许 list/form）: " + viewType);
            }
            if (!entityNames.contains(entityName)) {
                throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                        "视图引用了包外实体: " + entityName);
            }
            kernel.lifecycle().upsertViewForEntity(entityName, viewType,
                    view.path("name").asString(viewType),
                    view.has("columns") ? view.get("columns").toString() : null,
                    view.has("filters") ? view.get("filters").toString() : null);
        }
    }

    /** 归属校验：同名实体归属其他插件或平台（元数据管理创建）时拒绝，防静默覆盖。 */
    private void requireEntityOwnership(String entityName, String pluginId) {
        kernel.lifecycle().entityOwnerOf(entityName)
                .filter(owner -> !owner.equals(pluginId))
                .ifPresent(owner -> {
                    String ownerLabel = "<platform>".equals(owner) ? "平台（元数据管理创建）" : owner;
                    throw new PluginValidationException(ErrorCodes.REGISTRATION_FAILED,
                            "实体 " + entityName + " 已被 " + ownerLabel + " 注册，禁止跨归属覆盖");
                });
    }

    /** 内存注册（激活与重启恢复共用）：navigation/theme-asset 以契约类型进注册表。 */
    private void registerInMemory(String activationId, PluginVersionRecord version) {
        JsonNode entities = PluginContributionFactory.parseEntities(
                kernel.lifecycle().resourcePayloadsOf(version.id()));
        for (String navigationKey : PluginContributionFactory.navigationKeysOf(version)) {
            kernel.extensions().register(ExtensionPoints.NAVIGATION,
                    PluginContributionFactory.navigationContribution(navigationKey, version,
                            entities), activationId);
        }
        for (ThemeAssetSpec asset : PluginContributionFactory.themeAssetsOf(version)) {
            kernel.extensions().register(ExtensionPoints.THEME_ASSET,
                    PluginContributionFactory.themeAssetContribution(asset), activationId);
        }
    }

    /** 升级失败补偿：重激活旧版本；失败记事件（插件将无 ACTIVE 版本，属 P0/P1 缺陷路径）。 */
    private void restoreCurrentVersions(String actor, List<ActivationRecord> currents) {
        for (ActivationRecord current : currents) {
            try {
                activate(actor, current.pluginVersionId());
            } catch (RuntimeException rollbackFailure) {
                kernel.lifecycle().appendPluginEvent(current.pluginId(), current.id(),
                        "UPGRADE_ROLLBACK_FAILED");
            }
        }
    }

    private void rollback(ActivationRecord activation, RuntimeException error) {
        kernel.extensions().closeAll(activation.id());
        kernel.lifecycle().deleteRegistrations(activation.id());
        kernel.lifecycle().updateStatus(activation.id(), ActivationStatus.FAILED,
                errorStage(error), errorCode(error));
        kernel.lifecycle().appendPluginEvent(activation.pluginId(), activation.id(),
                "ACTIVATION_FAILED");
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
