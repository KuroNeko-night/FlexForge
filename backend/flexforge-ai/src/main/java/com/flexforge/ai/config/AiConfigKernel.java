package com.flexforge.ai.config;

import com.flexforge.common.PublicApi;

/**
 * 配置域协作者聚合（QG-4，构造器参数上限 5）：环境缺省、密钥加密与保存前
 * 探活闸的单一传递载体。Bean 注册见 {@link AiConfigWiring}。
 */
@PublicApi
public record AiConfigKernel(AiEnv env, SecretCipher cipher, ModelConfigGate gate) {
}
