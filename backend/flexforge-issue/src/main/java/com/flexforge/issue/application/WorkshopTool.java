package com.flexforge.issue.application;

import com.flexforge.common.PublicApi;
import com.flexforge.issue.domain.IssueRepository;

import java.util.Map;

/**
 * 需求工坊工具端口（FR-ISSUE-09，docs/13 §3.6-9）：模型只在提示词协议内
 * "调用"工具——输出是待校验数据，执行面收敛在本接口的白名单实现上
 * （Spring 注入收集，预留后续工具扩展位）。实现必须：以认证用户为操作者、
 * 复用既有服务校验与审计、不信任模型提供的任何身份类参数。
 */
@PublicApi
public interface WorkshopTool {

    /** 工具名（提示词协议中的 tool 字段值）。 */
    String name();

    /** 执行（args 来自模型输出，已按声明参数集字符串化；校验失败抛 IAE）。 */
    ToolResult execute(String operator, Map<String, String> args);

    /** 工具执行结果（issueId 非空=已创建对象，summary 供回话文本拼接）。 */
    @PublicApi
    record ToolResult(String issueId, String title, String summary) {

        public boolean created() {
            return issueId != null;
        }
    }

    /** 创建需求工具的参数声明（提示词协议口径）。 */
    String TITLE_ARG = "title";
    String DESCRIPTION_ARG = "description";

    /** 便捷构造（实现侧使用）。 */
    static ToolResult of(IssueRepository.IssueRecord issue, String summary) {
        return new ToolResult(issue.id(), issue.title(), summary);
    }
}
