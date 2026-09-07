package com.flexforge.plugin.application;

import com.flexforge.common.contract.ProcessorContribution;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.ActivationStatus;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginVersionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ActiveProcessorLocator.class);

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

    /** key → 激活处理器（不存在抛 processor_not_found 专用码异常，审查 P2-5：
     * 走通用 NoSuchElement 会返回 not_found 码与文档/前端契约失配）。 */
    ActiveProcessor findByKey(String key) {
        return activeProcessors().stream()
                .filter(processor -> processor.spec().key().equals(key))
                .findFirst()
                .orElseThrow(() -> new com.flexforge.plugin.domain.ProcessorExecutionException(
                        com.flexforge.common.api.ErrorCodes.PROCESSOR_NOT_FOUND,
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
            } else {
                // 正常被导入期双向核对挡住；DB 载荷损坏时留痕而非静默消失（审查 P3-5）
                log.warn("处理器脚本载荷缺失，跳过: {} {}", pluginId, spec.entry());
            }
        }
    }

    private static List<ProcessorContribution> contributionsOf(PluginVersionRecord version) {
        return PluginContributionFactory.processorsOf(version).stream()
                .map(PluginContributionFactory::processorContribution)
                .toList();
    }
}
