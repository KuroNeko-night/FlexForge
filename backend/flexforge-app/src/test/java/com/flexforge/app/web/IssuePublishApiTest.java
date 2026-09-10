package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
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
 * P23 FR-ISSUE-03B/07 API 回归：提示词 v3 三段简报随规格版本落库、
 * publish 门条件（本人/有效规格/简报齐备/幂等）、USER 视角服务端范围收口
 * （列表按创建者过滤、他人 Issue 详情/评论 403，S2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IssuePublishApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;
    private String developerBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
    }

    /** fixture 两轮澄清（首轮追问/次轮规格+三段简报）→ 确认推送幂等。 */
    @Test
    void userClarifyProducesBriefAndPublishIsIdempotent() throws Exception {
        String issueId = createIssue(userBearer, "三段简报", "记录澄清项，数量非负");
        mockMvc.perform(postClarify(issueId, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.specProduced").value(false))
                .andExpect(jsonPath("$.questions.length()").value(3));

        String round2 = mockMvc.perform(postClarify(issueId,
                        "名称+数量，数量非负，验收为 qty=-1 拒绝"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specProduced").value(true))
                .andExpect(jsonPath("$.brief.colloquial").isNotEmpty())
                .andExpect(jsonPath("$.brief.feasibility").isNotEmpty())
                .andExpect(jsonPath("$.brief.agentPrompt").isNotEmpty())
                .andExpect(jsonPath("$.spec.valid").value(true))
                .andReturn().getResponse().getContentAsString();
        // 简报随规格版本落库（SpecRevisionRecord.briefJson）
        String briefJson = JsonPath.read(round2, "$.spec.briefJson");
        assertThat(briefJson).contains("colloquial");

        String first = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/issues/" + issueId + "/publish")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String publishedAt = JsonPath.read(first, "$.publishedAt");
        // 幂等重入：不覆盖时间、不报错
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/publish")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedAt").value(publishedAt));
    }

    /** 无任何规格版本：publish 400（validation_error）。 */
    @Test
    void publishWithoutSpecRejected() throws Exception {
        String issueId = createIssue(userBearer, "无规格", "未澄清就推送");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/publish")
                        .header("Authorization", userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    /** 手工规格（开发者 PUT，无简报）：作者 publish 400——需经 AI 澄清产出简报。 */
    @Test
    void publishManualSpecWithoutBriefRejected() throws Exception {
        String issueId = createIssue(userBearer, "手工规格推送", "开发者手工保存规格");
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MANUAL_SPEC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/publish")
                        .header("Authorization", userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    /** 非作者 publish 403；USER 列表只含本人 Issue；他人 Issue 详情/评论 403。 */
    @Test
    void userScopeIsEnforcedServerSide() throws Exception {
        String mine = createIssue(userBearer, "我的需求", "用户视角");
        String others = createIssue(developerBearer, "开发者的", "他人 Issue");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + others + "/publish")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("permission_denied"));

        // 共享容器中同 USER 可能已有其他用例的 Issue：断言"含本人、不含他人"
        String list = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(list, "$[*].id");
        assertThat(ids).contains(mine).doesNotContain(others);

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + others)
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + others + "/comments")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + others + "/comments")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"越权评论\"}"))
                .andExpect(status().isForbidden());

        // 本人 Issue：详情/评论正常（用户参与讨论）
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + mine + "/comments")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"补充：需要导出\"}"))
                .andExpect(status().isOk());
        // 开发者视角不受收口（完整信息面）
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + mine)
                        .header("Authorization", developerBearer))
                .andExpect(status().isOk());
    }

    private static final String MANUAL_SPEC = """
            {"schemaVersion":1,"summary":"手工规格","entities":[
            {"name":"publish_item","displayName":"推送项","fields":[
            {"name":"name","displayName":"名称","fieldType":"text","required":true}]}],
            "acceptance":["可查询"]}
            """;

    private MockHttpServletRequestBuilder postClarify(String issueId, String answer) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
                .post("/api/v1/issues/" + issueId + "/clarify")
                .header("Authorization", userBearer)
                .contentType(MediaType.APPLICATION_JSON);
        return answer == null ? builder : builder.content("{\"answer\":\"" + answer + "\"}");
    }

    private String createIssue(String bearer, String title, String description) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
