package com.flexforge.plugin.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 处理器运行时装配（P23）：启用调度——产物 TTL 清理（ProcessorArtifactStore.
 * sweepExpired）周期任务。app 扫描 com.flexforge 自动装配。
 */
@Configuration
@EnableScheduling
public class ProcessorRuntimeConfig {
}
