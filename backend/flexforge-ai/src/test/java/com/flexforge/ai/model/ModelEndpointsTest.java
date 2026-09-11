package com.flexforge.ai.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 端点拼装（P26 官方教程口径）：根地址拼 /chat/completions、旧完整端点兼容。 */
class ModelEndpointsTest {

    @Test
    void chatEndpointAppendsSuffixToRoot() {
        assertThat(ModelEndpoints.chatOf("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(ModelEndpoints.chatOf("https://api.openai.com/v1/"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
    }

    @Test
    void chatEndpointKeepsLegacyFullEndpoint() {
        assertThat(ModelEndpoints.chatOf("https://api.example/v1/chat/completions"))
                .isEqualTo("https://api.example/v1/chat/completions");
    }

    @Test
    void modelsEndpointAlwaysOnRoot() {
        assertThat(ModelEndpoints.modelsOf("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/models");
        assertThat(ModelEndpoints.modelsOf("https://api.example/v1/chat/completions"))
                .isEqualTo("https://api.example/v1/models");
    }
}
