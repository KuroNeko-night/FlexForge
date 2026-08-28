package com.flexforge.plugin.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 校验管线装配（Kernel 为纯记录，经此注册为 Bean）。 */
@Configuration
class PluginImportConfig {

    @Bean
    PluginImportKernel pluginImportKernel(ArchiveInspector inspector, ManifestValidator validator,
                                          MigrationScriptScanner scanner, DependencyResolver resolver) {
        return new PluginImportKernel(inspector, validator, scanner, resolver);
    }
}
