package com.flexforge.app.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R-GOV-08（docs/11 §3）：日志不出现密码、JWT、API key 与完整 Authorization 头。
 * 携带敏感头/参数的真实请求穿过过滤器和控制器日志路径，捕获全部日志事件断言无泄漏。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LogMaskingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    // 低熵假值（gitleaks generic-api-key 不误报）；断言的是"值不进日志"，不依赖真实 JWT 形态
    private static final String LEAK_CANARY = "fake-jwt-token-for-masking-test";
    private static final String SECRET_PARAM = "supersecret-value-42";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void attachAppender() {
        // 用例级附加（非 @BeforeAll）：确保在 Spring 上下文/日志系统初始化完成之后挂载
        appender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        appender.list.clear();
    }

    @Test
    void requestLogsNeverContainSensitiveValues() throws Exception {
        mockMvc.perform(get("/api/v1/test/items")
                        .queryParam("pageSize", "20")
                        .queryParam("password", SECRET_PARAM)
                        .header("Authorization", "Bearer " + LEAK_CANARY))
                .andExpect(status().isOk());

        // 过滤器完成日志必须先出现（正控制：证明确实捕获到了日志）
        assertThat(appender.list).isNotEmpty();

        String allLogs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));

        assertThat(allLogs).doesNotContain("Bearer");
        assertThat(allLogs).doesNotContain(LEAK_CANARY);
        assertThat(allLogs).doesNotContain(SECRET_PARAM);
        assertThat(allLogs).doesNotContainIgnoringCase("authorization");
        assertThat(allLogs).doesNotContainIgnoringCase("password");
        // requestId 应出现在完成日志中（结构化日志基线）
        assertThat(allLogs).contains("req-");
    }
}
