package com.flexforge.common.contract;

import com.flexforge.common.PublicApi;

/**
 * 数据处理器内存贡献（extension.data-processor 契约类型，P20 / ADR-0002 Level 2）：
 * 激活注册进运行时注册表、停用/卸载随 activationId 撤销；执行时由
 * ProcessorService 按此定位脚本与输入实体。
 */
@PublicApi
public record ProcessorContribution(
        String key,
        String label,
        String kind,
        String entry,
        String inputEntity,
        String inputMode,
        java.util.List<String> accept,
        Integer maxInputMB) {

    /** P23：兼容旧载荷新增字段缺省（entity 模式无 accept/上限）。 */
    public ProcessorContribution {
        if (inputMode == null || inputMode.isBlank()) {
            inputMode = "entity";
        }
        if (accept == null) {
            accept = java.util.List.of();
        }
    }

    public boolean fileMode() {
        return "file".equals(inputMode);
    }
}
