package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;

/**
 * OpenAI 兼容端点拼装（P26，FR-SETUP-01）：base-url 语义对齐官方教程——配置填
 * API 根地址（如 https://api.deepseek.com），对话端点自动拼 /chat/completions；
 * 旧"完整端点"存量值（已 /chat/completions 结尾）向后兼容原样使用。
 */
@PublicApi
public final class ModelEndpoints {

    private static final String CHAT_SUFFIX = "/chat/completions";

    private ModelEndpoints() {
    }

    /** 根地址：去尾斜杠；旧完整端点值剥掉 /chat/completions 段。 */
    public static String rootOf(String baseUrl) {
        String root = baseUrl == null ? "" : baseUrl.strip();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.endsWith(CHAT_SUFFIX)) {
            root = root.substring(0, root.length() - CHAT_SUFFIX.length());
        }
        return root;
    }

    /** 对话端点：完整端点值原样，否则根地址 + /chat/completions。 */
    public static String chatOf(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.strip();
        return trimmed.endsWith(CHAT_SUFFIX) ? trimmed : rootOf(trimmed) + CHAT_SUFFIX;
    }

    /** 模型列表端点（保存探活用）：根地址 + /models（OpenAI/DeepSeek 标准）。 */
    public static String modelsOf(String baseUrl) {
        return rootOf(baseUrl) + "/models";
    }
}
