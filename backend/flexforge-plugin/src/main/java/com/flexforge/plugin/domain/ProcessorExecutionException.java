package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;

/**
 * 处理器执行失败（P20，ADR-0002 Level 2）：超时/非零退出/IO 超限/输出校验失败
 * 的统一异常形态；code 取 processor_failed / processor_output_invalid /
 * processor_input_too_large / processor_input_invalid / artifact_not_found /
 * artifact_expired（docs/09 P20 验收②与 P23 文件处理器的失败路径载体）。
 */
@PublicApi
public class ProcessorExecutionException extends RuntimeException {

    private final String code;

    public ProcessorExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ProcessorExecutionException failed(String message) {
        return new ProcessorExecutionException(ErrorCodes.PROCESSOR_FAILED, message);
    }

    public static ProcessorExecutionException outputInvalid(String message) {
        return new ProcessorExecutionException(ErrorCodes.PROCESSOR_OUTPUT_INVALID, message);
    }

    public static ProcessorExecutionException inputTooLarge(String message) {
        return new ProcessorExecutionException(ErrorCodes.PROCESSOR_INPUT_TOO_LARGE, message);
    }

    /** P23：文件输入三重校验失败（扩展名/魔数/大小）。 */
    public static ProcessorExecutionException inputInvalid(String message) {
        return new ProcessorExecutionException(ErrorCodes.PROCESSOR_INPUT_INVALID, message);
    }

    /** P23：产物不存在或无权访问（防枚举同码）。 */
    public static ProcessorExecutionException artifactNotFound(String message) {
        return new ProcessorExecutionException(ErrorCodes.ARTIFACT_NOT_FOUND, message);
    }

    /** P23：产物已过 TTL。 */
    public static ProcessorExecutionException artifactExpired(String message) {
        return new ProcessorExecutionException(ErrorCodes.ARTIFACT_EXPIRED, message);
    }

    public String code() {
        return code;
    }
}
