package com.flexforge.issue.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.issue.domain.IssueWorkshopRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 工坊协作者内核装配（P30）：WorkshopKernel 为纯记录（参数上限口径），
 * 须显式注册 Bean（与 AiConfigWiring.AiKernel 同模式）。
 */
@Configuration
public class IssueWorkshopWiring {

    @Bean
    IssueWorkshopService.WorkshopKernel workshopKernel(IssueWorkshopRepository workshop,
                                                       IssueAiService issueAi, ModelPort model,
                                                       AuditEventPort audit, Clock clock) {
        return new IssueWorkshopService.WorkshopKernel(workshop, issueAi, model, audit, clock);
    }
}
