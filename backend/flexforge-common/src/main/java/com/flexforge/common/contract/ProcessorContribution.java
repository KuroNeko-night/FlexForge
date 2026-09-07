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
        String inputEntity) {
}
