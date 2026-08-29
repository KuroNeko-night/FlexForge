package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 插件版本存储端口（plugin_instance/plugin_version/plugin_dependency）。
 * 写入入口 {@link #storeVersion} 事务化：实例不存在则建、版本与依赖同事务落库。
 */
@PublicApi
public interface PluginPackageRepository {

    /** 按主键查版本（生命周期入口）。 */
    Optional<PluginVersionRecord> findByVersionId(String versionId);

    /** 按 content hash 查版本（幂等导入命中）。 */
    Optional<PluginVersionRecord> findByContentHash(String contentHash);

    /** 卸载后幂等重导入：实例状态回 imported（非 uninstalled 则忽略，PR #28 审查 P3）。 */
    void resetUninstalledInstance(String pluginId);

    /** 按稳定 ID + 版本号查版本（同版本异内容冲突检测）。 */
    Optional<PluginVersionRecord> findVersion(String pluginId, String version);

    /** 某插件已导入的全部版本号（依赖解析用）。 */
    List<String> versionsOf(String pluginId);

    /**
     * 事务化落库：plugin_instance 不存在则插入，随后插入版本与依赖。
     * 唯一约束冲突（并发同包导入）上抛，由调用方按幂等语义复查。
     */
    PluginVersionRecord storeVersion(String pluginName, PluginVersionRecord version,
                                     List<DependencySpec> dependencies);

    /** 全部插件实例摘要（inventory 聚合用）。 */
    List<InstanceEntry> listInstances();

    /** 某插件版本摘要（inventory 聚合用，旧→新）。 */
    List<VersionEntry> versionSummariesOf(String pluginId);

    /** 插件实例摘要行（plugin_instance 镜像）。 */
    @PublicApi
    record InstanceEntry(String pluginId, String name, String status) {
    }

    /** 版本摘要行（不含载荷）。 */
    @PublicApi
    record VersionEntry(String versionId, String version, Instant createdAt) {
    }
}
