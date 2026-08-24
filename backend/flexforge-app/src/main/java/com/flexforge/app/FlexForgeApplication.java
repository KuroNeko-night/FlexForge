package com.flexforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 扫描 com.flexforge 以装配平台模块（auth/system 的组件与配置；common/runtime 为纯库）。
 */
@SpringBootApplication(scanBasePackages = "com.flexforge")
public class FlexForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlexForgeApplication.class, args);
    }
}
