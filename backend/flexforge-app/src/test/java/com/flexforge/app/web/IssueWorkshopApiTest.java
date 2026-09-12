package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求工坊 API 级验收（FR-ISSUE-09，V021，fixture 供应商）：对话开场追问 →
 * 信息足够后 AI 工具调用创建 issue（真实 CreateIssueWorkshopTool+clarify 落
 * 规格草稿）→ 会话回放带 issueId → 清空；会话按用户隔离；消息上限 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IssueWorkshopApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;
    private String adminBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    @AfterEach
    void resetWorkshop() {
        jdbc.update("DELETE FROM issue_workshop_message");
        jdbc.update("DELETE FROM requirement_spec");
        jdbc.update("DELETE FROM issue_comment");
        jdbc.update("DELETE FROM issue");
    }

    private MockHttpServletRequestBuilder post(String body, String bearer) {
        return MockMvcRequestBuilders.post("/api/v1/issues/workshop")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void workshopFlowAsksThenCreatesIssueWithSpec() throws Exception {
        // 首条消息：fixture 追问
        mockMvc.perform(post("{\"message\":\"我想要一个设备点检的需求\"}", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(
                        org.hamcrest.Matchers.containsString("字段")))
                .andExpect(jsonPath("$.issueId").doesNotExist());

        // 次条消息：信息足够 → 工具创建 + clarify 落规格
        String created = mockMvc.perform(post(
                        "{\"message\":\"设备点检：字段有设备名称、点检结果、备注，结果必填；验收标准是列表可建可筛\"}",
                        userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(
                        org.hamcrest.Matchers.containsString("已创建需求")))
                .andExpect(jsonPath("$.issueId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String issueId = JsonPath.read(created, "$.issueId");

        Integer specs = jdbc.queryForObject(
                "SELECT count(*) FROM requirement_spec WHERE issue_id = ?", Integer.class, issueId);
        assertThat(specs).isGreaterThanOrEqualTo(1);

        // 回放：assistant 消息携带 issueId；他人隔离
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/workshop")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].issueId").value(issueId));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/workshop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 清空
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/issues/workshop")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(4));
    }

    @Test
    void workshopValidatesMessageShape() throws Exception {
        mockMvc.perform(post("{\"message\":\"   \"}", userBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("{\"message\":\"" + "问".repeat(2001) + "\"}", userBearer))
                .andExpect(status().isBadRequest());
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM issue_workshop_message", Integer.class);
        assertThat(rows).isZero();
    }
}
