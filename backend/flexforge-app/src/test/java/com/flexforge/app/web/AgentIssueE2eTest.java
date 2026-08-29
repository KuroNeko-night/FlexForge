package com.flexforge.app.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.flexforge.app.web.AuthTestSupport.ADMIN_USERNAME;
import static com.flexforge.app.web.AuthTestSupport.DEVELOPER_USERNAME;
import static com.flexforge.app.web.AuthTestSupport.USER_USERNAME;
import static com.flexforge.app.web.AuthTestSupport.adminPassword;
import static com.flexforge.app.web.AuthTestSupport.developerPassword;
import static com.flexforge.app.web.AuthTestSupport.loginToken;
import static com.flexforge.app.web.AuthTestSupport.seedUsers;
import static com.flexforge.app.web.AuthTestSupport.userPassword;

/**
 * RB-E2E（docs/11 §4、docs/09 P12 验收一）：同一 Spring 上下文内把数据库
 * 重置为"只执行平台迁移"的干净状态后运行五场景完整演示，连续三轮——
 * 证明演示可重复、无隐藏状态依赖；各环节耗时由 E2eDemoScript 逐段打印
 * （CI 日志即论文/答辩耗时证据，对照 docs/00 §5 十分钟目标）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AgentIssueE2eTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final int ROUNDS = 3;

    /** 类级单例包（zip 时间戳漂移会改变 contentHash，只允许构建一次）。 */
    private static byte[] packageZip;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeAll
    static void buildPackage() throws Exception {
        packageZip = zipPackage(Path.of("..", "..", "plugins", "example-inventory"));
    }

    @Test
    void threeConsecutiveDemoRunsOnCleanDatabase() throws Exception {
        for (int iteration = 1; iteration <= ROUNDS; iteration++) {
            resetToCleanDatabase();
            E2eDemoScript demo = new E2eDemoScript(mockMvc, jdbc,
                    loginAllRoles(), packageZip, iteration);
            demo.runAllScenarios();
            System.out.print(demo.report());
        }
    }

    /** 重置为干净库：DROP SCHEMA → 重建 → 平台迁移重放 → 种子三角色。
     * 仅作用于 Testcontainers 一次性容器（R-GOV 红线不涉及生产数据）。 */
    private void resetToCleanDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        seedUsers(jdbc);
    }

    private E2eDemoScript.Actors loginAllRoles() throws Exception {
        return new E2eDemoScript.Actors(
                "Bearer " + loginToken(mockMvc, ADMIN_USERNAME, adminPassword()),
                "Bearer " + loginToken(mockMvc, DEVELOPER_USERNAME, developerPassword()),
                "Bearer " + loginToken(mockMvc, USER_USERNAME, userPassword()));
    }

    private static byte[] zipPackage(Path pluginDir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out);
                var walk = Files.walk(pluginDir)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                zos.putNextEntry(new ZipEntry(pluginDir.relativize(file)
                        .toString().replace('\\', '/')));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
