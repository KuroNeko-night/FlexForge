package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginPackageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 插件清单聚合（docs/03 §8 GET /plugins/inventory）：实例+版本+激活/失败诊断
 * 只读视图，供插件管理与答辩演示排障使用。
 */
@PublicApi
@Service
public class PluginInventoryService {

    /** 单插件清单项：实例摘要 + 版本摘要 + 最近激活记录（含失败阶段与错误码）。 */
    @PublicApi
    public record PluginInventoryEntry(
            String pluginId,
            String name,
            String instanceStatus,
            List<PluginPackageRepository.VersionEntry> versions,
            List<ActivationRecord> activations) {
    }

    private final PluginPackageRepository packages;
    private final LifecycleRepository lifecycle;

    public PluginInventoryService(PluginPackageRepository packages,
                                  LifecycleRepository lifecycle) {
        this.packages = packages;
        this.lifecycle = lifecycle;
    }

    public List<PluginInventoryEntry> inventory() {
        return packages.listInstances().stream()
                .map(instance -> new PluginInventoryEntry(instance.pluginId(), instance.name(),
                        instance.status(), packages.versionSummariesOf(instance.pluginId()),
                        lifecycle.activationsOf(instance.pluginId())))
                .toList();
    }
}
