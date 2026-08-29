package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;

/**
 * 模型端口（docs/09 P11、docs/13 §3.6）：模型访问的唯一收敛点，HTTP 与
 * fixture 两种实现；clarify/生成编排只依赖本端口，不感知具体供应商。
 * 密钥只经环境变量注入实现内部，不进入请求/响应对象（绝不入库与日志）。
 */
@PublicApi
public interface ModelPort {

    /** 单轮补全：入参提示词（已模板化），出参模型原始文本（调用方负责解析校验）。 */
    ModelReply complete(ModelRequest request);

    /** 模型标识（任务记录用，如 fixture-clarify-v1 / openai-compatible）。 */
    String name();

    @PublicApi
    record ModelRequest(String promptVersion, String prompt) {
    }

    @PublicApi
    record ModelReply(String text) {
    }
}
