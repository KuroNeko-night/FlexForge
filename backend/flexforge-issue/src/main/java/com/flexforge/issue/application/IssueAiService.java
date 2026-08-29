package com.flexforge.issue.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import com.flexforge.ai.spec.ClarifyEngine;
import com.flexforge.ai.spec.PluginPackageGenerator;
import com.flexforge.ai.spec.PromptTemplates;
import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.issue.domain.AiTaskLogPort;
import com.flexforge.issue.domain.IssueRepository;
import com.flexforge.issue.domain.IssueStatus;
import com.flexforge.plugin.application.PluginImportService;
import com.flexforge.plugin.application.PluginLifecycleService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

/**
 * Issue AI 编排（docs/09 P11、FR-ISSUE-03..05）：clarify 经 ModelPort 多轮澄清
 * 产出规格草稿（保存为规格新版本）；generate 从确认后的合法规格确定性生成
 * Level 1 包并走标准导入/激活（同权），成功推进 IN_TESTING、失败转 DEV_FAILED。
 * 每次调用落 ai_task_log（模型/提示词版本/轮次/重试/校验结果/耗时）。
 */
@PublicApi
@Service
public class IssueAiService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 插件域协作者内核（参数上限口径）。 */
    @PublicApi
    record AiKernel(IssueRepository repository, IssueWorkflowService workflow,
                    PluginImportService imports, PluginLifecycleService lifecycle) {
    }

    private final AiKernel kernel;
    private final ModelPort model;
    private final AiTaskLogPort taskLog;
    private final AuditEventPort audit;
    private final Clock clock;

    public IssueAiService(AiKernel kernel, ModelPort model, AiTaskLogPort taskLog,
                          AuditEventPort audit, Clock clock) {
        this.kernel = kernel;
        this.model = model;
        this.taskLog = taskLog;
        this.audit = audit;
        this.clock = clock;
    }

    /** 澄清结果：追问（下一轮由用户回答）或规格草稿（已存为新版本）。 */
    @PublicApi
    public record ClarifyOutcome(boolean specProduced, java.util.List<String> questions,
                                 IssueRepository.SpecRevisionRecord spec) {
    }

    /** 澄清（FR-ISSUE-03）：模板化提示词 → 模型 → 校验/重试 → 规格草稿版本。 */
    public ClarifyOutcome clarify(String operator, String issueId, String answer) {
        IssueRepository.IssueRecord issue = requireIssue(issueId);
        String prompt = PromptTemplates.render("clarify", Map.of(
                "title", issue.title(),
                "description", issue.description(),
                "answer", answer == null || answer.isBlank() ? "（无）" : answer));
        long started = System.nanoTime();
        try {
            ClarifyEngine.ClarifyResult result = new ClarifyEngine(model).clarify(prompt);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            taskLog.insert(new AiTaskLogPort.TaskLogEntry(issueId, "clarify", model.name(),
                    PromptTemplates.VERSION, 1, result.modelAttempts() - 1, true, null,
                    durationMs));
            if (!result.specProduced()) {
                return new ClarifyOutcome(false, result.questions(), null);
            }
            IssueRepository.SpecRevisionRecord revision =
                    kernel.workflow().saveSpec(operator, issueId, result.spec());
            audit.record(AuditEvents.of(operator, "issue.clarify", issueId,
                    "spec#" + revision.revision(), clock));
            return new ClarifyOutcome(true, java.util.List.of(), revision);
        } catch (ClarifyEngine.ModelOutputInvalidException e) {
            logFailure(issueId, e.attempts, e.code());
            throw e;
        } catch (ModelUnavailableException e) {
            logFailure(issueId, 0, e.code());
            throw e;
        }
    }

    /** 生成（FR-ISSUE-05）：确认后规格 → Level 1 包 → 标准导入+激活 → IN_TESTING。 */
    @PublicApi
    public record GenerateOutcome(String pluginId, String versionId, String activationId,
                                  IssueRepository.IssueRecord issue) {
    }

    public GenerateOutcome generate(String operator, String issueId)
            throws java.io.IOException {
        IssueRepository.IssueRecord issue = requireIssue(issueId);
        if (issue.status() != IssueStatus.APPROVED) {
            throw new IllegalArgumentException("只有已批准的 Issue 可以生成（当前: "
                    + issue.status().displayName() + "）");
        }
        IssueRepository.SpecRevisionRecord spec = kernel.repository().latestSpec(issueId);
        if (spec == null || !spec.valid()) {
            throw new IllegalArgumentException("缺少已确认的合法规格，不能生成");
        }
        long started = System.nanoTime();
        try {
            JsonNode specJson = JSON.readTree(
                    spec.specJson().getBytes(StandardCharsets.UTF_8));
            PluginPackageGenerator.GeneratedPackage pkg =
                    PluginPackageGenerator.generate(issueId, specJson);
            var preview = kernel.imports().importPackage(operator, pkg.zip());
            var activation = kernel.lifecycle().activate(operator, preview.versionId());
            IssueRepository.IssueRecord updated =
                    kernel.workflow().transition(operator, issueId, IssueStatus.IN_TESTING, null);
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            taskLog.insert(new AiTaskLogPort.TaskLogEntry(issueId, "generate",
                    "deterministic-generator", PromptTemplates.VERSION, 1, 0, true, null,
                    durationMs));
            audit.record(AuditEvents.of(operator, "issue.generate",
                    issueId + "/" + pkg.pluginId(), "success", clock));
            return new GenerateOutcome(pkg.pluginId(), preview.versionId(), activation.id(),
                    updated);
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            taskLog.insert(new AiTaskLogPort.TaskLogEntry(issueId, "generate",
                    "deterministic-generator", PromptTemplates.VERSION, 1, 0, false,
                    errorCodeOf(e), durationMs));
            markDevFailed(operator, issueId, e);
            throw e;
        }
    }

    private void markDevFailed(String operator, String issueId, RuntimeException cause) {
        if (requireIssue(issueId).status() == IssueStatus.APPROVED) {
            String reason = cause.getMessage() == null ? "生成失败" : cause.getMessage();
            kernel.workflow().transition(operator, issueId, IssueStatus.DEV_FAILED,
                    reason.length() > 500 ? reason.substring(0, 500) : reason);
        }
    }

    private static String errorCodeOf(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    private void logFailure(String issueId, int retries, String errorCode) {
        taskLog.insert(new AiTaskLogPort.TaskLogEntry(issueId, "clarify", model.name(),
                PromptTemplates.VERSION, 1, retries, false, errorCode, 0));
    }

    private IssueRepository.IssueRecord requireIssue(String issueId) {
        IssueRepository.IssueRecord issue = kernel.repository().findIssue(issueId);
        if (issue == null) {
            throw new java.util.NoSuchElementException("Issue 不存在: " + issueId);
        }
        return issue;
    }
}
