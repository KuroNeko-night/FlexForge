package com.flexforge.meta.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-META 单点断言（docs/09 P04 验收：字段类型映射只有 FieldTypeRegistry 一处）：
 * 动态扫描 backend/ 全部模块的 main 源码，六个类型名字面量（含 V004 存储兜底之外的任何
 * Java 出现点）只允许在 FieldTypeRegistry.java。新增分支逻辑必须经由 registry 契约。
 */
class FieldTypeRegistrySinglePointTest {

    private static final Pattern TYPE_LITERAL = Pattern.compile("\"(text|integer|decimal|date|enum|boolean)\"");

    @Test
    void fieldTypeLiteralsExistOnlyInRegistry() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : moduleMainSourceRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals("FieldTypeRegistry.java"))
                        .forEach(path -> scanFile(path, violations));
            }
        }
        assertThat(violations)
                .as("字段类型名只允许出现在 FieldTypeRegistry（RB-META 单点；DB 兜底在 V004 迁移）")
                .isEmpty();
    }

    /** 定位模块源码根：动态发现 backend/ 下含 src/main/java 的模块（新模块自动纳入扫描）。
     *  surefire 工作目录在模块 basedir（backend/ 的子目录）；IDE 从仓库根运行时回退 backend/ 前缀。 */
    private static List<Path> moduleMainSourceRoots() throws IOException {
        Path backendRoot = Files.exists(Path.of("..", "flexforge-common", "src", "main", "java"))
                ? Path.of("..") : Path.of("backend");
        assertThat(Files.isDirectory(backendRoot)).as("后端模块根应存在").isTrue();
        try (Stream<Path> modules = Files.list(backendRoot)) {
            List<Path> roots = modules.filter(Files::isDirectory)
                    .map(module -> module.resolve(Path.of("src", "main", "java")))
                    .filter(Files::exists)
                    .toList();
            assertThat(roots.size()).as("至少应扫描到本模块").isGreaterThanOrEqualTo(1);
            return roots;
        }
    }

    private static void scanFile(Path file, List<String> violations) {
        try {
            if (TYPE_LITERAL.matcher(Files.readString(file)).find()) {
                violations.add(file.toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取源码失败: " + file, e);
        }
    }
}
