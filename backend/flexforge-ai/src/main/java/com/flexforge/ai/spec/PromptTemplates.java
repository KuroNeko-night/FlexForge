package com.flexforge.ai.spec;

import com.flexforge.common.PublicApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 提示词模板装载（docs/09 P11：模板按版本文件化于 prompts/v1/，代码不散落
 * 提示词字符串；fixture 与提示词版本一一对应）。
 */
@PublicApi
public final class PromptTemplates {

    public static final String VERSION = "v1";

    private static final Map<String, String> CACHE = new HashMap<>();

    private PromptTemplates() {
    }

    /** 按名装载模板并以参数填充 {@code {{key}}} 占位。参数值视为数据段原样嵌入
     * （docs/13 §3.6-2，用户输入不做二次转义），由模型侧约束与 Schema 校验兜底。 */
    public static String render(String name, Map<String, String> params) {
        String template = CACHE.computeIfAbsent(name, PromptTemplates::load);
        for (var entry : params.entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return template;
    }

    private static String load(String name) {
        String path = "/prompts/" + VERSION + "/" + name + ".md";
        try (InputStream in = PromptTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("提示词模板缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词模板读取失败: " + path, e);
        }
    }
}
