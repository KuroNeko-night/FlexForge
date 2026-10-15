package com.flexforge.app.web;

import com.flexforge.plugin.application.PluginImportService;
import com.flexforge.plugin.application.PluginLifecycleService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Issue #30 P2-5: FR-ISSUE-05/06, RB-AI checked I/O failure compensation. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IssueGenerateIoFailureApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PluginImportService imports;

    @MockitoBean
    private PluginLifecycleService lifecycle;

    private String developerBearer;

    @BeforeEach
    void seedAndLogin() {
        AuthTestSupport.seedUsers(jdbc);
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
    }

    @Test
    void importIoFailureRecordsTaskAndCompensatesWithoutActivating() throws Exception {
        String issueId = approvedIssue();
        when(imports.importPackage(anyString(), any(byte[].class)))
                .thenThrow(new IOException("private-io-detail"));

        mvc.perform(post("/api/v1/issues/" + issueId + "/generate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.message").value("服务内部错误"));

        assertThat(jdbc.queryForObject("SELECT status FROM issue WHERE id = ?",
                String.class, issueId)).isEqualTo("DEV_FAILED");
        var logs = jdbc.queryForList(
                "SELECT output_valid, error_code, duration_ms FROM ai_task_log"
                        + " WHERE issue_id = ? AND kind = 'generate'", issueId);
        assertThat(logs).singleElement().satisfies(row -> {
            assertThat(row.get("output_valid")).isEqualTo(false);
            assertThat(row.get("error_code")).isEqualTo("IOException");
            assertThat(((Number) row.get("duration_ms")).longValue()).isGreaterThanOrEqualTo(0);
        });
        assertFailureAudit(issueId);
        verify(lifecycle, never()).upgrade(anyString(), anyString());
    }

    private void assertFailureAudit(String issueId) {
        assertThat(jdbc.queryForList(
                "SELECT reason FROM issue_transition WHERE issue_id = ? AND to_status = ?",
                issueId, "DEV_FAILED")).singleElement()
                .satisfies(row -> assertThat(row.get("reason")).isEqualTo("生成失败"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event"
                        + " WHERE object_id = ? AND action = 'issue.transition'"
                        + " AND result = 'DEV_FAILED'", Integer.class, issueId)).isEqualTo(1);
    }

    private String approvedIssue() throws Exception {
        String body = mvc.perform(post("/api/v1/issues")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"IO failure\",\"description\":\"retryable generation\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mvc.perform(put("/api/v1/issues/" + id + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"schemaVersion":1,"summary":"IO recovery","entities":[
                                {"name":"io_item","displayName":"IO item","fields":[
                                {"name":"name","displayName":"Name","fieldType":"text"}]}],
                                "acceptance":["query records"]}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/issues/" + id + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        return id;
    }
}
