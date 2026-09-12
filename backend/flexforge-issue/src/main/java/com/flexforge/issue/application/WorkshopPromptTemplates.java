package com.flexforge.issue.application;

import com.flexforge.common.PublicApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 工坊提示词模板装载（docs/09 P30 红线：文件化不散落字符串；与 kb/clarify
 * 模板各自独立演进）。v1=双态 JSON 工具调用协议（reply | create_issue）。
 */
@PublicApi
public final class WorkshopPromptTemplates {

    public static final String VERSION = "workshop-v1";

    private static final String TEMPLATE = load();

    private WorkshopPromptTemplates() {
    }

    public static String render(Map<String, String> params) {
        String out = TEMPLATE;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }

    private static String load() {
        String path = "/prompts/workshop-v1.md";
        try (InputStream in = WorkshopPromptTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("工坊提示词模板缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("工坊提示词模板读取失败: " + path, e);
        }
    }
}
