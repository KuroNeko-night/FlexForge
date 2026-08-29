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
 * （含 {@code $tag$} 带标签形态）内容替换为空串、行/块注释剥除——防字符串
 * 字面量内的 `--`/`/*` 把越界语句从扫描中隐藏（PR #19 审查 P1），同时消除
 * 字符串含平台前缀的假阳性。
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
            requirePlainCharacters(name, sql);
            requireNoPlatformObjects(name, sql);
            checksums.put(name, sha256(sql));
        }
        return checksums;
    }

    /** 执行前二次校验（纵深防御，docs/13 §4）：命名 + 字符集 + 越界。 */
    void requireExecutable(String scriptName, String sql) {
        requireOrderedName(scriptName, 0);
        requirePlainCharacters(scriptName, sql);
        requireNoPlatformObjects(scriptName, sql);
    }

    /**
     * 字符集白名单：拒绝反斜杠。合法顺序 DDL/DML 几乎不含 `\`；同时封死
     * E-string/U&'...' 反斜杠转义引号与词法机的闭合点分歧（PR #19 复审 N1）。
     */
    private static void requirePlainCharacters(String name, String sql) {
        if (sql.indexOf('\\') >= 0) {
            throw PluginValidationException.invalidManifest(
                    "迁移脚本含反斜杠（Level 1 迁移字符集不允许）: " + name);
        }
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
        return dollarDelimiterAt(sql, i) != null;
    }

    /**
     * 提取位置 i 处的 dollar-quote 定界符（PG 语法 {@code $标签$}，标签可空）。
     * 标签字符取字母/数字/下划线（含多字节字母）：比 PG 的无引号标识符规则略宽，
     * 偏差方向安全——标签不合规的裸 {@code $..$} 在 PG 里是语法错误，扫描器按
     * 字符串跳过只会导致执行期拒绝（fail-closed），不会放行越界语句；反之若比
     * PG 窄（如漏认 {@code $表$}），词法机会在字符串内容里的单引号处误开字符串、
     * 吞掉后续真正的越界语句（本次审计 P1，与 PR #19 的 -- 绕过同构）。
     *
     * <p>前驱守卫：PG 无引号标识符的**后续**字符允许 {@code $}（{@code zz$e$}
     * 是单个标识符而非 dollar-quote 起点），故 {@code $} 紧跟标识符字符或
     * {@code $} 时不是定界符——否则词法机会在标识符中部误开字符串、把越界语句
     * 吞进"字符串内容"（PR #31 交叉审查 P1，含空标签紧贴 {@code zz$$} 形态）。
     * 数字前缀（{@code 1$e$}）PG 按数字字面量结束后走 dollar-quote，此处按
     * 标识符延续拒绝——扫描器看到更多代码只会假阳性拒绝（fail-closed，可接受）。
     */
    private static String dollarDelimiterAt(String sql, int i) {
        if (sql.charAt(i) != '$') {
            return null;
        }
        if (i > 0 && (isTagChar(sql.charAt(i - 1)) || sql.charAt(i - 1) == '$')) {
            return null;
        }
        int end = i + 1;
        while (end < sql.length() && isTagChar(sql.charAt(end))) {
            end++;
        }
        boolean closed = end < sql.length() && sql.charAt(end) == '$';
        return closed ? sql.substring(i, end + 1) : null;
    }

    private static boolean isTagChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
        // 未闭合引号吞至结尾：PostgreSQL 同样拒绝该脚本，扫描侧漏扫即 fail-closed
        return i;
    }

    private static int skipDollarQuoted(String sql, int start, StringBuilder out) {
        out.append("''");
        String delimiter = dollarDelimiterAt(sql, start);
        // 只认同标签定界符：PG 允许 $a$ $b$ .. $b$ $a$ 嵌套，内层异标签属外层内容
        int close = sql.indexOf(delimiter, start + delimiter.length());
        // 未闭合吞至结尾：PostgreSQL 同样拒绝该脚本，扫描侧漏扫即 fail-closed
        return close < 0 ? sql.length() : close + delimiter.length();
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
