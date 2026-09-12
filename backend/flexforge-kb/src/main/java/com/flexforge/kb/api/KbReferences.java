package com.flexforge.kb.api;

import com.flexforge.common.PublicApi;
import com.flexforge.kb.application.KbAssistantService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用条目 JSON 解析（控制器回放历史消息用；写入侧序列化在
 * KbAssistantService，本类只读）。
 */
@PublicApi
final class KbReferences {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private KbReferences() {
    }

    static List<KbAssistantService.Reference> parse(String referencesJson) {
        JsonNode array = JSON.readTree(referencesJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (!array.isArray()) {
            return List.of();
        }
        List<KbAssistantService.Reference> references = new ArrayList<>();
        for (JsonNode node : array) {
            references.add(new KbAssistantService.Reference(
                    node.path("id").asString(null),
                    node.path("title").asString(""),
                    node.path("category").asString(null)));
        }
        return List.copyOf(references);
    }
}
