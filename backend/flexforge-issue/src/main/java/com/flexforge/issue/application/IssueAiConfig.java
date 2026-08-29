package com.flexforge.issue.application;

import com.flexforge.issue.domain.IssueRepository;
import com.flexforge.plugin.application.PluginImportService;
import com.flexforge.plugin.application.PluginLifecycleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Issue AI 编排装配（AiKernel 为纯记录，经此注册为 Bean）。 */
@Configuration
class IssueAiConfig {

    @Bean
    IssueAiService.AiKernel issueAiKernel(IssueRepository repository,
                                          IssueWorkflowService workflow,
                                          PluginImportService imports,
                                          PluginLifecycleService lifecycle) {
        return new IssueAiService.AiKernel(repository, workflow, imports, lifecycle);
    }
}
