package com.flexforge.plugin.application;

import com.flexforge.common.contract.ProcessorContribution;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.ActivationStatus;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginVersionRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 激活处理器定位器（P20）：展开 ACTIVE 激活的 data-processor 声明与脚本字节
 * （asset_payloads Base64 解码）；key 命名约定反向域名前缀，跨插件同名取最先
 * 激活者（包内唯一由 manifest 校验保证）。
 */
@Component
public class ActiveProcessorLocator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 激活处理器定位：spec（manifest 展开）+ 脚本字节。 */
    record ActiveProcessor(String activationId, String pluginId,
                           ProcessorContribution spec, byte[] script) {
    }

    private final PluginPackageRepository packages;
    private final LifecycleRepository lifecycle;

    public ActiveProcessorLocator(PluginPackageRepository packages, LifecycleRepository lifecycle) {
        this.packages = packages;
        this.lifecycle = lifecycle;
    }

    /** key → 激活处理器（不存在抛 NoSuchElement，404 processor_not_found 语义由映射层兜底）。 */
    ActiveProcessor findByKey(String key) {
        return activeProcessors().stream()
                .filter(processor -> processor.spec().key().equals(key))
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "处理器不存在或所属插件未激活: " + key));
    }

    List<ActiveProcessor> activeProcessors() {
        List<ActiveProcessor> result = new ArrayList<>();
        for (var instance : packages.listInstances()) {
            for (ActivationRecord activation : lifecycle.activationsOf(instance.pluginId())) {
                appendProcessors(result, instance.pluginId(), activation);
            }
        }
        return result;
    }

    private void appendProcessors(List<ActiveProcessor> result, String pluginId,
                                  ActivationRecord activation) {
        if (activation.status() != ActivationStatus.ACTIVE) {
            return;
        }
        PluginVersionRecord version = packages.findByVersionId(activation.pluginVersionId())
                .orElse(null);
        if (version == null) {
            return;
        }
        JsonNode payloads = JSON.readTree(lifecycle.assetPayloadsOf(version.id()));
        for (ProcessorContribution spec : contributionsOf(version)) {
            JsonNode encoded = payloads.path(spec.entry());
            if (encoded.isTextual()) {
                result.add(new ActiveProcessor(activation.id(), pluginId, spec,
                        Base64.getDecoder().decode(encoded.asText())));
            }
        }
    }

    private static List<ProcessorContribution> contributionsOf(PluginVersionRecord version) {
        return PluginContributionFactory.processorsOf(version).stream()
                .map(PluginContributionFactory::processorContribution)
                .toList();
    }
}
