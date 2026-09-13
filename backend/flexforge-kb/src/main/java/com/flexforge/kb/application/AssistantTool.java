package com.flexforge.kb.application;

import com.flexforge.common.PublicApi;

import java.util.Map;

/**
 * AI 助手工具端口（FR-KB-07，docs/13 §3.6-10，P30 用户补充）：助手可在
 * 提示词协议内调用工具了解业务插件内容（当前=平台内置的实体元数据检查；
 * 预留扩展位——新工具实现本接口并以 Spring Bean 注入即自动注册）。
 * 插件作者无需写代码适配：实体/字段的 displayName 质量决定工具呈现效果
 * （见开发 Skill 的适配指南）。实现只读，返回值为提示词数据段文本。
 */
@PublicApi
public interface AssistantTool {

    /** 工具名（提示词协议中的 tool 字段值）。 */
    String name();

    /** 一行用途说明（进提示词的工具清单）。 */
    String description();

    /** 取数：parameters 来自模型输出的键值表；键值不合法抛 IAE（消息回灌提示词）。 */
    String apply(Map<String, String> parameters);
}
