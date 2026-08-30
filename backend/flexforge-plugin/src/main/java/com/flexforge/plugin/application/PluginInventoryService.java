package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.ActivationStatus;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginVersionRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件清单聚合（docs/03 §8 GET /plugins/inventory）：实例+版本+激活/失败诊断
 * 只读视图，供插件管理与答辩演示排障使用；P12.5 增当前生效主题资产视图
 * （GET /plugins/theme-assets，前端壳层换肤消费面）。
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

    /** 当前生效主题资产声明（P12.5）：前端按 activationId+path 拼 serve URL 取回。 */
    @PublicApi
    public record ActiveThemeAsset(
            String activationId,
            String pluginId,
            String key,
            String kind,
            String path,
            String scope) {
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

    /** 遍历 ACTIVE 激活的版本载荷，展开 themeAssets 声明（含 tokens，P12.5 换肤通道）。 */
    public List<ActiveThemeAsset> activeThemeAssets() {
        List<ActiveThemeAsset> result = new ArrayList<>();
        for (PluginPackageRepository.InstanceEntry instance : packages.listInstances()) {
            for (ActivationRecord activation : lifecycle.activationsOf(instance.pluginId())) {
                if (activation.status() != ActivationStatus.ACTIVE) {
                    continue;
                }
                packages.findByVersionId(activation.pluginVersionId())
                        .map(PluginContributionFactory::themeAssetsOf)
                        .ifPresent(specs -> specs.forEach(spec -> result.add(
                                new ActiveThemeAsset(activation.id(), instance.pluginId(),
                                        spec.key(), spec.kind(), spec.path(), spec.scope()))));
            }
        }
        return List.copyOf(result);
    }
}
