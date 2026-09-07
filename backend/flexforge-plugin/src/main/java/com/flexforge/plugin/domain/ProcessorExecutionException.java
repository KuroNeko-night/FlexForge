package com.flexforge.plugin.domain;

import com.flexforge.common.api.ErrorCodes;

/**
 * 处理器执行失败（P20，ADR-0002 Level 2）：超时/非零退出/IO 超限/输出校验失败
 * 的统一异常形态；code 取 processor_failed / processor_output_invalid /
 * processor_input_too_large（docs/09 P20 验收②的失败路径载体）。
 */
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

    public String code() {
        return code;
    }
}
