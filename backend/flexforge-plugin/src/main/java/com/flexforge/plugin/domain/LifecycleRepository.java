package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.List;
import java.util.Optional;

/** 生命周期持久化端口（plugin_activation/plugin_registration/plugin_migration）。 */
@PublicApi
public interface LifecycleRepository {

    Optional<ActivationRecord> findActivation(String activationId);

    /** 同插件当前占用槽位（STARTING/ACTIVE）的激活——唯一性判据（docs/07 §3）。 */
    Optional<ActivationRecord> findOccupying(String pluginId);

    /** 幂等：同 version+operation 且非终态的既有激活。 */
    Optional<ActivationRecord> findActiveOperation(String pluginVersionId, String operation);

    /** 历史同 version 已成功完成的 operation 激活（重复安装返回既有结果）。 */
    Optional<ActivationRecord> findSucceeded(String pluginVersionId, String operation);

    ActivationRecord insertActivation(String id, String pluginId, String pluginVersionId,
                                      String operation, String requestedBy);

    int updateStatus(String activationId, ActivationStatus status, String stage, String errorCode);

    /** 插件当前 ACTIVE 激活（重启恢复/停用入口）。 */
    List<ActivationRecord> findActiveByPlugin(String pluginId);

    /** 全平台 ACTIVE 激活（应用重启恢复）。 */
    List<ActivationRecord> findAllActive();

    int insertRegistration(String activationId, String extensionType, String registrationKey,
                           String payloadJson);

    List<RegistrationEntry> registrationsOf(String activationId);

    int deleteRegistrations(String activationId);

    /** 迁移 runner 记录（ADR-0005：同事务写入）。 */
    int insertMigration(String pluginVersionId, String activationId, String scriptName,
                        String checksum);

    /** 已应用脚本 checksum（重复安装跳过判据）。 */
    List<MigrationEntry> migrationsOf(String pluginVersionId);

    /** 从 plugin_version 读 resource_payloads（注册编排用）。 */
    String resourcePayloadsOf(String pluginVersionId);

    /** 从 plugin_version 读 asset_payloads（资产 serve 端点用）。 */
    String assetPayloadsOf(String pluginVersionId);

    /** 实体注册接口：metadata 贡献写 meta_entity/meta_field（复用 P04 服务）。 */
    String insertEntityWithFields(String entityName, String displayName, String pluginId,
                                  List<FieldSpec> fields);

    /** 实体名当前归属插件（空=未注册）；跨插件同名在服务层拒绝（防静默覆盖）。 */
    Optional<String> entityOwnerOf(String entityName);

    int deactivateEntity(String pluginId);

    /** plugin_audit_event 追加事件（区别于平台审计）。 */
    void appendPluginEvent(String pluginId, String activationId, String eventType);

    /** 实体定义记录（registration payload 用）。 */
    record FieldSpec(String name, String displayName, String fieldType, boolean required,
                     String validationJson, String defaultValueJson, int position) {
    }

    @PublicApi
    record RegistrationEntry(String activationId, String extensionType,
                             String registrationKey, String payloadJson) {
    }

    @PublicApi
    record MigrationEntry(String pluginVersionId, String activationId, String scriptName,
                          String checksum) {
    }
}
