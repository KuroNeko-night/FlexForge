package com.flexforge.data.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-DATA 单点断言：data_record 表只允许 JdbcRecordRepository 一处访问——
 * 扫描 backend/ 全部模块 main 源码，"data_record" 字面量不得出现在其他任何类
 * （动态数据读写只经 service.data-access，docs/09 P05 验收）。
 */
class RecordAccessSinglePointTest {

    private static final String TABLE_LITERAL = "data_record";

    private static final String ALLOWED_FILE = "JdbcRecordRepository.java";

    @Test
    void dataTableLiteralExistsOnlyInRecordRepository() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : moduleMainSourceRoots()) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.getFileName().toString().equals(ALLOWED_FILE))
                        .forEach(path -> {
                            try {
                                if (Files.readString(path).contains(TABLE_LITERAL)) {
                                    violations.add(path.toString());
                                }
                            } catch (IOException e) {
                                throw new IllegalStateException("读取源码失败: " + path, e);
                            }
                        });
            }
        }
        assertThat(violations)
                .as("data_record 只允许 JdbcRecordRepository 访问（RB-DATA 单点）")
                .isEmpty();
    }

    /** 动态发现 backend/ 下含 src/main/java 的模块（新模块自动纳入）。 */
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
}
