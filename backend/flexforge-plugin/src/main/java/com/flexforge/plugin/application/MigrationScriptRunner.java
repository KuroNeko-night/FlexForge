package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.common.api.ErrorCodes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 插件迁移脚本执行器（ADR-0005：Level 1 受控 DDL/DML）。
 *
 * <p>安全边界：脚本内容来自 plugin_version.script_payloads（ADMIN 上传 +
 * P07 导入期词法状态机/反斜杠禁令/平台对象越界扫描/checksum）；
 * 执行前二次校验（docs/13 §4 纵深防御）：命名/字符集/越界——不依赖上游单点。
 * 仅接受单语句（分号分隔已由导入期声明，此处整块执行）。
 */
@PublicApi
@Component
public class MigrationScriptRunner {

    private final JdbcTemplate jdbc;
    private final MigrationScriptScanner scanner;

    public MigrationScriptRunner(JdbcTemplate jdbc, MigrationScriptScanner scanner) {
        this.jdbc = jdbc;
        this.scanner = scanner;
    }

    /**
     * 从 plugin_version.script_payloads 读取并执行一个迁移脚本。
     * SQL 内容不经过调用方（服务层零 SQL 文本接触），直接从版本存储读取，
     * 执行前二次校验（docs/13 §4 纵深防御）。
     */
    public void executeFromVersion(String scriptName, String pluginVersionId) {
        String sql;
        try {
            sql = jdbc.queryForObject(
                    "SELECT script_payloads ->> ? FROM plugin_version WHERE id = ?",
                    String.class, scriptName, pluginVersionId);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new PluginValidationException(ErrorCodes.MIGRATION_FAILED,
                    "迁移脚本读取失败（" + scriptName + "）: " + e.getClass().getSimpleName());
        }
        // jsonb ->> 对 JSON null 返回 SQL NULL；字面 "null" 为读取路径的防御性兜底
        if (sql == null || sql.equals("null") || sql.isEmpty()) {
            return;
        }
        try {
            scanner.requireExecutable(scriptName, sql);
            jdbc.execute(sql);
        } catch (PluginValidationException e) {
            throw new PluginValidationException(ErrorCodes.MIGRATION_FAILED,
                    "迁移脚本执行失败（" + scriptName + "）: " + e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            throw new PluginValidationException(ErrorCodes.MIGRATION_FAILED,
                    "迁移脚本执行失败（" + scriptName + "）: " + e.getClass().getSimpleName());
        }
    }
}
