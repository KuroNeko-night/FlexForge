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
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件迁移脚本校验层（ADR-0005 runner 骨架的导入期检查）：
 * `migrations/V<序号>__<名称>.sql` 命名与严格递增序、平台对象越界静态拒绝
 * （sys_/meta_/data_/plugin_ 前缀）、逐脚本 sha256 checksum。
 *
 * <p>越界扫描先做字符串感知的词法清洗：单引号（'' 转义）与 dollar-quote
 * 内容替换为空串、行/块注释剥除——防字符串字面量内的 `--`/`/*` 把越界语句
 * 从扫描中隐藏（PR #19 审查 P1），同时消除字符串含平台前缀的假阳性。
 * 执行与 plugin_migration 记录在 P08 安装期由 runner 完成（同事务）。
 */
@Component
public class MigrationScriptScanner {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("^migrations/V(\\d{1,5})__([a-z][a-z0-9_]*)\\.sql$");
    private static final Pattern PLATFORM_OBJECT =
            Pattern.compile("\\b(sys_|meta_|data_|plugin_)[a-z0-9_]*", Pattern.CASE_INSENSITIVE);

    /** 校验并计算 checksums（LinkedHashMap 保持声明序）。 */
    public Map<String, String> scan(List<String> declared, Function<String, byte[]> bytesOf) {
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
        Matcher matcher = PLATFORM_OBJECT.matcher(sanitize(sql));
        if (matcher.find()) {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "迁移脚本越界引用平台对象（仅允许插件自有前缀对象）: " + name + " → " + matcher.group());
        }
    }

    /** 字符串感知清洗：注释剥除、字符串内容清空（返回可安全做对象扫描的文本）。 */
    static String sanitize(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            i = dispatch(sql, i, out);
        }
        return out.toString();
    }

    /** 词法分派：返回消费后的下一位置，输出到 out（字符串/注释按占位替换）。 */
    private static int dispatch(String sql, int i, StringBuilder out) {
        char c = sql.charAt(i);
        if (c == '\'') {
            return skipQuoted(sql, i, out);
        }
        if (isDollarQuoteStart(sql, i)) {
            return skipDollarQuoted(sql, i, out);
        }
        if (isLineCommentStart(sql, i)) {
            return skipLineComment(sql, i);
        }
        if (isBlockCommentStart(sql, i)) {
            return skipBlockComment(sql, i, out);
        }
        out.append(c);
        return i + 1;
    }

    private static boolean isDollarQuoteStart(String sql, int i) {
        return sql.charAt(i) == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$';
    }

    private static boolean isLineCommentStart(String sql, int i) {
        return sql.charAt(i) == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-';
    }

    private static boolean isBlockCommentStart(String sql, int i) {
        return sql.charAt(i) == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*';
    }

    private static int skipQuoted(String sql, int start, StringBuilder out) {
        out.append("''");
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == '\'') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipDollarQuoted(String sql, int start, StringBuilder out) {
        out.append("''");
        int i = start + 2;
        while (i + 1 < sql.length()
                && !(sql.charAt(i) == '$' && sql.charAt(i + 1) == '$')) {
            i++;
        }
        return Math.min(i + 2, sql.length());
    }

    private static int skipLineComment(String sql, int start) {
        int i = start;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int start, StringBuilder out) {
        out.append(' ');
        int i = start + 2;
        while (i + 1 < sql.length()
                && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
            i++;
        }
        return Math.min(i + 2, sql.length());
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
