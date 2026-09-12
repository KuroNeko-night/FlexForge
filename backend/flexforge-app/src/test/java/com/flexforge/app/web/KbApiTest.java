package com.flexforge.app.web;

import com.flexforge.ai.model.ModelPort;
import com.flexforge.ai.model.ModelUnavailableException;
import com.flexforge.ai.model.RoutingModelPort;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
 * 知识库与 AI 助手 API 级验收（FR-KB-01..04，V018）：条目 CRUD 与权限
 * （ADMIN 写/登录读）、尺寸校验 400、ask 检索引用与消息成对落库、会话
 * 用户隔离与清空、模型不可用 503 不落半截会话、审计行。默认 provider=fixture
 * （确定性回答，不依赖外部模型）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class KbApiTest {

    /** 503 场景可武装桩：默认直通真实 RoutingModelPort（fixture），武装后抛
     * model_unavailable——同上下文单容器覆盖成败两路径。 */
    @TestConfiguration
    static class ArmableModelConfig {
        @Bean
        @Primary
        ModelPort armableModelPort(RoutingModelPort real) {
            return new ModelPort() {
                @Override
                public ModelReply complete(ModelRequest request) {
                    if (Armable.UNAVAILABLE) {
                        throw new ModelUnavailableException(
                                ModelUnavailableException.REASON_OFFLINE, "上游模型不可用（测试桩）");
                    }
                    return real.complete(request);
                }

                @Override
                public String name() {
                    return real.name();
                }
            };
        }
    }

    static final class Armable {
        static volatile boolean UNAVAILABLE;
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    @AfterEach
    void resetData() {
        jdbc.update("DELETE FROM kb_chat_message");
        jdbc.update("DELETE FROM kb_entry");
        jdbc.update("DELETE FROM sys_audit_event WHERE action LIKE 'kb.%'");
        Armable.UNAVAILABLE = false;
    }

    private MockHttpServletRequestBuilder adminPost(String path, String body) {
        return MockMvcRequestBuilders.post(path)
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void entryCrudIsAdminOnlyAndValidated() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/entries")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk());
        String id = createEntry("差旅报销规范", "财务制度", "30 日内提交");
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/kb/entries/" + id)
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"差旅报销规范 v2\",\"category\":null,\"content\":\"60 日内\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("差旅报销规范 v2"));
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/kb/entries/" + id)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"content\":\"y\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/kb/entries/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/kb/entries/" + id)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        mockMvc.perform(adminPost("/api/v1/kb/entries",
                        "{\"title\":\"" + "标".repeat(121) + "\",\"content\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/kb/entries")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"c\"}"))
                .andExpect(status().isForbidden());
        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action LIKE 'kb.entry.%'", Integer.class);
        assertThat(auditRows).isEqualTo(3);
    }

    @Test
    void askReturnsReferencedAnswerAndPersistsOwnConversation() throws Exception {
        String id = createEntry("差旅报销规范", "财务制度", "员工出差后 30 日内提交报销单");
        mockMvc.perform(adminPost("/api/v1/kb/ask", "{\"question\":\"怎么报销差旅费用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.references[0].id").value(id))
                .andExpect(jsonPath("$.answer").value(
                        org.hamcrest.Matchers.containsString("差旅报销规范")));
        // 成对落库 + 本人可见 + 他人隔离
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/messages")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].references[0].title").value("差旅报销规范"));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/messages")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // 清空仅本人
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/kb/messages")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(0));
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/kb/messages")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(2));
        Integer pair = jdbc.queryForObject(
                "SELECT count(*) FROM kb_chat_message", Integer.class);
        assertThat(pair).isZero();
    }

    @Test
    void askValidatesQuestionShape() throws Exception {
        mockMvc.perform(adminPost("/api/v1/kb/ask", "{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        mockMvc.perform(adminPost("/api/v1/kb/ask",
                        "{\"question\":\"" + "问".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        Integer messages = jdbc.queryForObject(
                "SELECT count(*) FROM kb_chat_message", Integer.class);
        assertThat(messages).isZero();
    }

    @Test
    void modelUnavailableYields503WithoutPartialConversation() throws Exception {
        createEntry("差旅报销规范", null, "内容");
        Armable.UNAVAILABLE = true;
        try {
            mockMvc.perform(adminPost("/api/v1/kb/ask", "{\"question\":\"报销流程\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("model_unavailable"));
        } finally {
            Armable.UNAVAILABLE = false;
        }
        Integer messages = jdbc.queryForObject(
                "SELECT count(*) FROM kb_chat_message", Integer.class);
        assertThat(messages).isZero();
        Integer failureAudits = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'kb.ask'"
                        + " AND result = 'failure'", Integer.class);
        assertThat(failureAudits).isEqualTo(1);
    }

    @Test
    void multipartAskStoresExtractsAndServesAttachmentsToOwnerOnly() throws Exception {
        createEntry("差旅报销规范", "财务制度", "员工出差后提交报销单");
        String askBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/kb/ask")
                                .file(new MockMultipartFile("files", "报销单.csv",
                                        "text/csv", "物料,金额\nA01,300"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                .file(new MockMultipartFile("files", "现场.png",
                                        "image/png", new byte[] {1, 2, 3}))
                                .param("question", "帮我核对附件里的数据")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(
                        org.hamcrest.Matchers.containsString("报销单.csv")))
                .andExpect(jsonPath("$.attachments.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String attachmentId = JsonPath.read(askBody, "$.attachments[0].id");

        // 回放带附件视图
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/messages")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachments.length()").value(2));

        // 本人下载 200，他人 404 防枚举
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/attachments/" + attachmentId)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/attachments/" + attachmentId)
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM kb_attachment", Integer.class);
        assertThat(rows).isEqualTo(2);
        String extracted = jdbc.queryForObject(
                "SELECT extracted_text FROM kb_attachment WHERE filename = '报销单.csv'",
                String.class);
        assertThat(extracted).contains("A01,300");
    }

    @Test
    void multipartAskRejectsBadExtensionAndOversizeFile() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/kb/ask")
                        .file(new MockMultipartFile("files", "工具.exe",
                                "application/x-msdownload", "x".getBytes()))
                        .param("question", "看看这个")
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/kb/ask")
                        .file(new MockMultipartFile("files", "超大.pdf",
                                "application/pdf", big))
                        .param("question", "看看这个")
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest());
        Integer messages = jdbc.queryForObject(
                "SELECT count(*) FROM kb_chat_message", Integer.class);
        assertThat(messages).isZero();
    }

    @Test
    void longOfficeMimeAndOversizeMimeAreStoredSafely() throws Exception {
        // 审查 P2-4：live 曾因 71 字符 Office MIME 超 V019 列宽 500——
        // V020 拓宽 + 服务端 255 截断的回归锁定
        String officeMime = "application/vnd.openxmlformats-officedocument"
                + ".wordprocessingml.document";
        String storedId = JsonPath.read(mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/kb/ask")
                                .file(new MockMultipartFile("files", "报告.docx", officeMime,
                                        "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                .param("question", "看看")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.attachments[0].id");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/kb/attachments/" + storedId)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());

        String hugeMime = "x/" + "a".repeat(300);
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/kb/ask")
                        .file(new MockMultipartFile("files", "怪类型.txt", hugeMime,
                                "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("question", "看看")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        Integer overlong = jdbc.queryForObject(
                "SELECT count(*) FROM kb_attachment WHERE char_length(content_type) > 255",
                Integer.class);
        assertThat(overlong).isZero();
    }

    private String createEntry(String title, String category, String content) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"category\":"
                + (category == null ? "null" : "\"" + category + "\"")
                + ",\"content\":\"" + content + "\"}";
        String response = mockMvc.perform(adminPost("/api/v1/kb/entries", body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }
}
