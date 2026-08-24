package com.flexforge.app;

import com.flexforge.runtime.InMemoryExtensionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 扫描 com.flexforge 以装配平台模块（auth/system 的组件与配置；common/runtime 为纯库）。
 */
@SpringBootApplication(scanBasePackages = "com.flexforge")
public class FlexForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlexForgeApplication.class, args);
    }

    /** 运行时注册表（extension.navigation 等）：MVP 内存实现，P08 接入插件激活生命周期。 */
    @Bean
    public InMemoryExtensionRegistry inMemoryExtensionRegistry() {
        return new InMemoryExtensionRegistry();
    }
}
