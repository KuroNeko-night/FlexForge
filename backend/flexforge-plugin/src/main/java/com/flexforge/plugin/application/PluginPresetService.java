package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.PresetRepository;
import com.flexforge.plugin.domain.PresetRepository.PresetItem;
import com.flexforge.plugin.domain.PresetRepository.PresetRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * 插件预设（P21，FR-PLUGIN-12）：保存当前启用集合快照；应用=收敛——先停用预设外
 * 的启用插件，再按预设版本切换/激活（复用 upgrade：占用冲突自动停旧、失败补偿回
 * 旧版）。逐项执行、单项失败不中断；依赖缺失类失败在其余条目激活后重试一轮。
 * 每项 stop/upgrade 的审计与占用/幂等口径由既有生命周期端口承载。
 */
@PublicApi
@Service
public class PluginPresetService {

    /** 协作者内核（参数上限口径）。 */
    record PresetKernel(PresetRepository presets, PluginPackageRepository packages,
                        LifecycleRepository lifecycle) {
    }

    private final PresetKernel kernel;
    private final PluginLifecycleService lifecycleActions;
    private final AuditEventPort audit;
    private final Clock clock;

    public PluginPresetService(PresetKernel kernel, PluginLifecycleService lifecycleActions,
                               AuditEventPort audit, Clock clock) {
        this.kernel = kernel;
        this.lifecycleActions = lifecycleActions;
        this.audit = audit;
        this.clock = clock;
    }

    /** 应用结果：逐项上报（成功激活/成功停用/失败明细）。 */
    @PublicApi
    public record ApplyResult(List<String> activated, List<String> stopped,
                              List<ApplyFailure> failed) {
    }

    /** 单项失败：action ∈ {stop, activate}，message 截断至 200 字符。 */
    @PublicApi
    public record ApplyFailure(String pluginId, String action, String message) {
    }

    public List<PresetRecord> list() {
        return kernel.presets().listAll();
    }

    /** 保存当前启用集合快照（含版本）；允许空集合（"全停"场景）。 */
    public PresetRecord save(String actor, String name) {
        String trimmed = requireValidName(name);
        if (kernel.presets().nameExists(trimmed)) {
            throw new IllegalArgumentException("同名预设已存在: " + trimmed);
        }
        List<PresetItem> entries = new ArrayList<>();
        for (PluginPackageRepository.InstanceEntry instance
                : kernel.packages().listInstances()) {
            kernel.lifecycle().findOccupying(instance.pluginId()).ifPresent(activation
                    -> kernel.packages().findByVersionId(activation.pluginVersionId())
                    .ifPresent(version -> entries.add(new PresetItem(version.pluginId(),
                            version.id(), version.version()))));
        }
        PresetRecord preset = new PresetRecord("preset-" + UUID.randomUUID(), trimmed,
                entries, actor, clock.instant());
        kernel.presets().insert(preset);
        audit.record(AuditEvents.of(actor, "plugin.preset.save", preset.id(),
                "entries=" + entries.size(), clock));
        return preset;
    }

    public ApplyResult apply(String actor, String presetId) {
        PresetRecord preset = requirePreset(presetId);
        List<String> stopped = new ArrayList<>();
        List<EntryFailure> failures = new ArrayList<>();
        stopOutsidePreset(actor, pluginIdsOf(preset), stopped, failures);
        List<String> activated = new ArrayList<>();
        activateEntries(actor, preset.entries(), activated, failures);
        retryDependencyFailures(actor, activated, failures);
        audit.record(AuditEvents.of(actor, "plugin.preset.apply", preset.id(),
                "ok=" + (activated.size() + stopped.size()) + " fail=" + failures.size(), clock));
        return new ApplyResult(List.copyOf(activated), List.copyOf(stopped),
                failures.stream().map(f -> new ApplyFailure(f.entry().pluginId(), f.action(),
                        f.message())).toList());
    }

    public void delete(String actor, String presetId) {
        requirePreset(presetId);
        kernel.presets().delete(presetId);
        audit.record(AuditEvents.of(actor, "plugin.preset.delete", presetId, "success", clock));
    }

    /** 阶段一：停用预设外的启用插件（占用含 STARTING，与切换口径一致）。 */
    private void stopOutsidePreset(String actor, Set<String> targets, List<String> stopped,
                                   List<EntryFailure> failures) {
        for (PluginPackageRepository.InstanceEntry instance
                : kernel.packages().listInstances()) {
            String pluginId = instance.pluginId();
            if (targets.contains(pluginId)) {
                continue;
            }
            ActivationRecord occupying = kernel.lifecycle().findOccupying(pluginId).orElse(null);
            if (occupying == null) {
                continue;
            }
            try {
                lifecycleActions.stop(actor, occupying.id());
                stopped.add(pluginId);
            } catch (RuntimeException e) {
                failures.add(new EntryFailure(new PresetItem(pluginId, occupying.pluginVersionId(),
                        ""), "stop", messageOf(e), false));
            }
        }
    }

    /** 阶段二：按预设切换/激活（upgrade 语义：同版本幂等、异版本自动切换）。 */
    private void activateEntries(String actor, List<PresetItem> entries, List<String> activated,
                                 List<EntryFailure> failures) {
        for (PresetItem entry : entries) {
            try {
                lifecycleActions.upgrade(actor, entry.versionId());
                activated.add(entry.pluginId());
            } catch (RuntimeException e) {
                failures.add(new EntryFailure(entry, "activate", messageOf(e),
                        isDependencyMissing(e)));
            }
        }
    }

    /** 依赖缺失重试一轮：预设条目间依赖在其余条目激活后即可满足。 */
    private void retryDependencyFailures(String actor, List<String> activated,
                                         List<EntryFailure> failures) {
        for (int i = 0; i < failures.size(); i++) {
            EntryFailure failure = failures.get(i);
            if (!failure.retryable()) {
                continue;
            }
            try {
                lifecycleActions.upgrade(actor, failure.entry().versionId());
                activated.add(failure.entry().pluginId());
                failures.remove(i);
                i--;
            } catch (RuntimeException e) {
                failures.set(i, new EntryFailure(failure.entry(), failure.action(),
                        messageOf(e), false));
            }
        }
    }

    private PresetRecord requirePreset(String presetId) {
        return kernel.presets().find(presetId)
                .orElseThrow(() -> new NoSuchElementException("预设不存在: " + presetId));
    }

    private static Set<String> pluginIdsOf(PresetRecord preset) {
        Set<String> ids = new HashSet<>();
        for (PresetItem entry : preset.entries()) {
            ids.add(entry.pluginId());
        }
        return ids;
    }

    private static String requireValidName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 50) {
            throw new IllegalArgumentException("预设名称需为 1-50 个字符");
        }
        return trimmed;
    }

    private static boolean isDependencyMissing(RuntimeException e) {
        return e instanceof PluginValidationException pve
                && ErrorCodes.DEPENDENCY_MISSING.equals(pve.code());
    }

    private static String messageOf(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    /** 内部失败暂存：retryable=依赖缺失（其余条目激活后可重试）。 */
    private record EntryFailure(PresetItem entry, String action, String message,
                                boolean retryable) {
    }
}
