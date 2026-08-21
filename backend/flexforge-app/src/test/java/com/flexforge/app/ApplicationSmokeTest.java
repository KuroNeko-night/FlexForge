package com.flexforge.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P01 启动冒烟：一次性容器中验证应用可启动、V001 平台迁移已执行、健康端点可用
 * （docs/09 P01 验收标准的自动化证据；数据库隔离规则见 docs/04 §2）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApplicationSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void healthEndpointReportsUp() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String body = client.get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void flywayCreatedPlatformTables() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name like 'sys_%'",
                Integer.class);
        assertThat(tables).isGreaterThanOrEqualTo(3);
    }

    @Test
    void onlyPlatformTablesExist() {
        // 骨架纯净性：平台迁移不得创建任何业务表（ADR-0004，R-GOV-09 的前置断言）
        Integer nonPlatform = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' "
                        + "and table_name not like 'sys_%' "
                        + "and table_name not like 'flyway_%'",
                Integer.class);
        assertThat(nonPlatform).isZero();
    }
}
