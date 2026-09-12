package com.flexforge.issue.application;

import com.flexforge.common.PublicApi;
import com.flexforge.issue.domain.IssueRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * create_issue 工具（FR-ISSUE-09）：经既有 IssueService.create 执行——
 * 校验/审计/creator=认证用户全部复用，模型输出只提供 title/description
 * 两个内容参数（docs/13 §3.6-9：不信任模型提供的任何身份类参数）。
 */
@PublicApi
@Component
public class CreateIssueWorkshopTool implements WorkshopTool {

    private final IssueService issues;

    public CreateIssueWorkshopTool(IssueService issues) {
        this.issues = issues;
    }

    @Override
    public String name() {
        return "create_issue";
    }

    @Override
    public ToolResult execute(String operator, Map<String, String> args) {
        String title = args.getOrDefault(TITLE_ARG, "");
        String description = args.getOrDefault(DESCRIPTION_ARG, "");
        IssueRepository.IssueRecord created = issues.create(
                operator, title, description, List.of());
        return WorkshopTool.of(created, "需求已创建");
    }
}
