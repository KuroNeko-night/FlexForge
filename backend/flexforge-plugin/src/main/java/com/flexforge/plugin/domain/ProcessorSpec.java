package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.util.List;
import java.util.Objects;

/**
 * 数据处理器声明（extension.data-processor 载荷，ADR-0002 Level 2 / P20，
 * P23 增文件输入模式）：插件携带 scripts/*.py 脚本，激活时注册；实体模式
 * 平台组装实体数据经子进程受控执行，文件模式（P23，FR-PLUGIN-14）argv[1]
 * 传输入文件路径、FLEXFORGE_OUTPUT_DIR 指定输出目录；stdout JSON 按输出
 * 契约校验（S6 修订）。kind 现仅 python。
 */
@PublicApi
public record ProcessorSpec(String key, String label, String kind, String entry,
                            String inputEntity, String inputMode, List<String> accept,
                            Integer maxInputMB) {

    /** 处理器实现语言白名单（运行时按 kind 分派执行器；MVP 仅 python）。 */
    public static final List<String> KINDS = List.of("python");

    /** 输入模式（P23）：entity=实体数据（缺省）；file=用户上传文件。 */
    public static final String MODE_ENTITY = "entity";
    public static final String MODE_FILE = "file";

    /** 文件模式扩展名白名单（平台级，声明 accept 只能取子集，docs/13 §3.5-5）。 */
    public static final List<String> ACCEPTABLE_EXT = List.of("csv", "xlsx", "txt");

    /** 文件模式平台级大小上限（MB，docs/13 §3.5-5 唯一来源）。 */
    public static final int MAX_INPUT_MB = 5;

    public boolean fileMode() {
        return MODE_FILE.equals(inputMode);
    }

    public ProcessorSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(inputEntity, "inputEntity");
        if (inputMode == null || inputMode.isBlank()) {
            inputMode = MODE_ENTITY;
        }
        if (accept == null) {
            accept = List.of();
        }
    }
}
