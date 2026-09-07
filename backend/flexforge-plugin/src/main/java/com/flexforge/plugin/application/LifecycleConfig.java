package com.flexforge.plugin.application;

import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.plugin.domain.LifecycleRepository;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PresetRepository;
import com.flexforge.runtime.InMemoryExtensionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** 生命周期装配（Kernel 为纯记录，经此注册为 Bean）。 */
@Configuration
class LifecycleConfig {

    @Bean
    PluginLifecycleService.LifecycleKernel lifecycleKernel(
            LifecycleRepository lifecycle,
            PluginPackageRepository packages,
            MigrationScriptRunner scriptRunner,
            MetaRegistry metaRegistry,
            InMemoryExtensionRegistry extensions) {
        return new PluginLifecycleService.LifecycleKernel(
                lifecycle, packages, scriptRunner, metaRegistry, extensions);
    }

    /** P21：预设协作者内核（纯记录，经此注册为 Bean）。 */
    @Bean
    PluginPresetService.PresetKernel presetKernel(
            PresetRepository presets,
            PluginPackageRepository packages,
            LifecycleRepository lifecycle) {
        return new PluginPresetService.PresetKernel(presets, packages, lifecycle);
    }
}
