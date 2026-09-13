package com.flexforge.kb.application;

import com.flexforge.common.PublicApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 助手提示词模板装载（docs/09 P28/P29 红线：提示词文件化，代码不散落字符串）。
 * 与 flexforge-ai 的 clarify 模板分版本演进（版本常量随审计记录），文件位于
 * 本模块资源 prompts/kb/；参数值视为数据段原样嵌入（docs/13 §3.6-7/8）。
 * v2=P29：附件数据段；v3=P30：工具调用协议（list_entities/inspect_entity）+业务实体索引数据段；v1/v2 为历史保留。
 */
@PublicApi
public final class KbPromptTemplates {

    public static final String VERSION = "kb-assistant-v3";

    private static final String TEMPLATE = load();

    private KbPromptTemplates() {
    }

    /** 以参数填充 {@code {{key}}} 占位（数据段原样嵌入，不做二次转义——模型侧约束兜底）。 */
    public static String render(Map<String, String> params) {
        String out = TEMPLATE;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }

    private static String load() {
        String path = "/prompts/kb/assistant-v3.md";
        try (InputStream in = KbPromptTemplates.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("助手提示词模板缺失: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("助手提示词模板读取失败: " + path, e);
        }
    }
}
