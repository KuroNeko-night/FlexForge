package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * 数据处理器声明（extension.data-processor 载荷，ADR-0002 Level 2 / P20）：
 * 插件携带 scripts/*.py 脚本，激活时注册；平台组装实体数据经子进程受控执行，
 * stdout JSON 按输出契约校验（S6 修订）。kind 现仅 python。
 */
@PublicApi
public record ProcessorSpec(String key, String label, String kind, String entry,
                            String inputEntity) {

    /** 处理器实现语言白名单（运行时按 kind 分派执行器；MVP 仅 python）。 */
    public static final List<String> KINDS = List.of("python");

    public ProcessorSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(inputEntity, "inputEntity");
    }
}
