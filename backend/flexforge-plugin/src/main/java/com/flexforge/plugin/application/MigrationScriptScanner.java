package com.flexforge.plugin.application;

import com.flexforge.common.api.ErrorCodes;
import com.flexforge.plugin.domain.PluginValidationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件迁移脚本校验层（ADR-0005 runner 骨架的导入期检查）：
 * `V<序号>__<名称>.sql` 命名与严格递增序、平台表越界静态拒绝
 * （sys_/meta_/data_/plugin_ 前缀对象，注释剥除后扫描）、逐脚本 sha256 checksum。
 * 执行与 plugin_migration 记录在 P08 安装期由 runner 完成（同事务）。
 */
@Component
public class MigrationScriptScanner {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("^migrations/V(\\d{1,5})__([a-z][a-z0-9_]*)\\.sql$");
    private static final Pattern PLATFORM_OBJECT =
            Pattern.compile("\\b(sys_|meta_|data_|plugin_)[a-z0-9_]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_COMMENT = Pattern.compile("--.*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 校验并计算 checksums（LinkedHashMap 保持声明序）。 */
    public Map<String, String> scan(List<String> declared, java.util.function.Function<String, byte[]> bytesOf) {
        long previous = 0;
        Map<String, String> checksums = new LinkedHashMap<>();
        for (String name : declared) {
            long ordinal = requireOrderedName(name, previous);
            previous = ordinal;
            String sql = new String(bytesOf.apply(name), StandardCharsets.UTF_8);
            requireNoPlatformObjects(name, sql);
            checksums.put(name, sha256(sql));
        }
        return checksums;
    }

    private static long requireOrderedName(String name, long previous) {
        Matcher matcher = NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            throw PluginValidationException.invalidManifest(
                    "迁移脚本名必须形如 V<序号>__<名称>.sql: " + name);
        }
        long ordinal = Long.parseLong(matcher.group(1));
        if (ordinal <= previous) {
            throw PluginValidationException.invalidManifest(
                    "迁移脚本序号必须严格递增: " + name + "（前一序号 " + previous + "）");
        }
        return ordinal;
    }

    private static void requireNoPlatformObjects(String name, String sql) {
        String withoutComments = BLOCK_COMMENT.matcher(LINE_COMMENT.matcher(sql).replaceAll("")).replaceAll("");
        Matcher matcher = PLATFORM_OBJECT.matcher(withoutComments);
        if (matcher.find()) {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "迁移脚本越界引用平台对象（仅允许插件自有前缀对象）: " + name + " → " + matcher.group());
        }
    }

    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
