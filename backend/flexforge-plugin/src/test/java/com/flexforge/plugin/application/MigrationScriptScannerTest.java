package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.PluginValidationException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 迁移脚本校验层（ADR-0005：命名/顺序/越界平台对象/checksum）。 */
class MigrationScriptScannerTest {

    private final MigrationScriptScanner scanner = new MigrationScriptScanner();

    private Map<String, String> scan(List<String> names, String... contents) {
        java.util.Iterator<String> it = List.of(contents).iterator();
        Function<String, byte[]> bytesOf = name -> it.next().getBytes(StandardCharsets.UTF_8);
        return scanner.scan(names, bytesOf);
    }

    @Test
    void validScriptsGetOrderedChecksums() {
        Map<String, String> checksums = scan(
                List.of("migrations/V001__init.sql", "migrations/V002__seed.sql"),
                "CREATE TABLE inv_item (id INT);",
                "INSERT INTO inv_item VALUES (1);");
        assertThat(checksums).containsOnlyKeys("migrations/V001__init.sql", "migrations/V002__seed.sql");
        assertThat(checksums.get("migrations/V001__init.sql"))
                .isEqualTo(MigrationScriptScanner.sha256("CREATE TABLE inv_item (id INT);"));
    }

    @Test
    void namingAndOrderingEnforced() {
        assertThatThrownBy(() -> scan(List.of("migrations/init.sql"), "SELECT 1;"))
                .hasMessageContaining("V<序号>__<名称>.sql");
        assertThatThrownBy(() -> scan(
                List.of("migrations/V002__a.sql", "migrations/V002__b.sql"), "SELECT 1;", "SELECT 2;"))
                .hasMessageContaining("严格递增");
    }

    @Test
    void platformObjectsRejectedEvenInMixedCase() {
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "DROP TABLE sys_user;"))
                .hasMessageContaining("越界")
                .hasMessageContaining("sys_user");
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "UPDATE META_FIELD SET name = 'x';"))
                .hasMessageContaining("越界");
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "INSERT INTO plugin_version VALUES ('x');"))
                .hasMessageContaining("越界");
    }

    @Test
    void commentsMentioningPlatformTablesDoNotTrigger() {
        Map<String, String> checksums = scan(List.of("migrations/V001__ok.sql"),
                "-- 平台表 sys_user 由 Flyway 管理，此处不碰\n"
                        + "/* meta_field 也不碰 */\nCREATE TABLE inv_item (id INT);");
        assertThat(checksums).containsKey("migrations/V001__ok.sql");
    }

    @Test
    void stringLiteralsCannotHidePlatformObjectsViaCommentTokens() {
        // PR #19 审查 P1：字符串内的 -- 不能把后半行从扫描中剥除
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "INSERT INTO inv_x VALUES ('a--b'); DROP TABLE sys_user;"))
                .hasMessageContaining("越界")
                .hasMessageContaining("sys_user");
        // 字符串内的 /* 不能触发跨段块注释剥除
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "SELECT '/*'; DELETE FROM sys_user; SELECT '*/'"))
                .hasMessageContaining("越界");
        // dollar-quote 内容同样占位替换（字符串数据非对象引用）；越界对象在引号外
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "SELECT $$some data$$; DELETE FROM meta_entity;"))
                .hasMessageContaining("越界")
                .hasMessageContaining("meta_entity");
    }

    @Test
    void backslashRejectedToCloseEscapeStringBypass() {
        // PR #19 复审 N1：E'a\'' 依赖反斜杠转义引号构造闭合点分歧——字符集白名单整体拒绝
        assertThatThrownBy(() -> scan(List.of("migrations/V001__evil.sql"),
                        "SELECT E'a\\''; DROP TABLE sys_user;"))
                .hasMessageContaining("反斜杠");
    }

    @Test
    void plainStringsContainingPlatformPrefixesAreNotFalsePositives() {
        // 字符串内容按占位替换：合法插件存字符串 'sys_user' 不误报
        Map<String, String> checksums = scan(List.of("migrations/V001__ok.sql"),
                "INSERT INTO inv_item (note) VALUES ('mention of sys_user and meta_field');");
        assertThat(checksums).containsKey("migrations/V001__ok.sql");
    }
}
