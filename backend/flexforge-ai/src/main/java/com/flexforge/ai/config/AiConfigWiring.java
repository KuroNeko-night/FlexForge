package com.flexforge.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置域 Kernel 装配（P26）：纯记录聚合协作者必须显式注册 Bean
 * （否则容器缺 Bean 只在集成测试暴露）。
 */
@Configuration
public class AiConfigWiring {

    @Bean
    AiConfigKernel aiConfigKernel(AiEnv env, SecretCipher cipher, ModelConfigGate gate) {
        return new AiConfigKernel(env, cipher, gate);
    }
}
