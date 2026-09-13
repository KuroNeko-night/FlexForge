package com.flexforge.kb.application;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.kb.domain.KbChatRepository;
import com.flexforge.kb.domain.KbEntryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 助手协作者内核装配（P30）：KbKernel 为纯记录（参数上限口径），须显式
 * 注册 Bean（与 AiConfigWiring.AiKernel 同模式）。
 */
@Configuration
public class KbWiring {

    @Bean
    KbAssistantService.KbKernel kbKernel(KbEntryRepository entries, KbChatRepository chat,
                                         ModelPort model, AuditEventPort audit, Clock clock) {
        return new KbAssistantService.KbKernel(entries, chat, model, audit, clock);
    }
}
