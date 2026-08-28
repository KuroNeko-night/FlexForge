package com.flexforge.plugin.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动恢复（docs/07 §5-8）：重建全部 ACTIVE 激活的内存注册（菜单/renderer）。
 * 单个插件恢复失败由服务层标记 FAILED；执行器整体异常只记日志，不阻断应用启动。
 */
@Component
class PluginRestoreRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PluginRestoreRunner.class);

    private final PluginLifecycleService lifecycle;

    PluginRestoreRunner(PluginLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int restored = lifecycle.restoreActivePlugins();
            if (restored > 0) {
                log.info("插件重启恢复：{} 个 ACTIVE 激活已重建内存注册", restored);
            }
        } catch (RuntimeException e) {
            log.error("插件重启恢复执行异常", e);
        }
    }
}
