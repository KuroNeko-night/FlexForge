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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-AI（docs/11：固定 fixture 生成、输出 Schema 校验、重试上限已由
 * flexforge-ai 单测覆盖；本类覆盖 API 链路）：fixture 澄清两轮→规格草稿、
 * 手工规格兜底→生成→导入激活→IN_TESTING→普通用户 CRUD、
 * 生成失败转 DEV_FAILED、clarify 权限（作者或开发者）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IssueAiApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String MANUAL_SPEC = """
            {"schemaVersion":1,"summary":"手工规格","entities":[
            {"name":"manualgen_item","displayName":"手工项","fields":[
            {"name":"name","displayName":"名称","fieldType":"text","required":true}]}],
            "acceptance":["可查询"]}
            """;

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

    // ===== FR-ISSUE-03：fixture 两轮澄清 → 规格草稿版本 + 任务记录 =====
    @Test
    void fixtureClarifyProducesQuestionsThenSpecDraft() throws Exception {
        String issueId = createIssue("AI 澄清", "库存数量不能为负");

        String round1 = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specProduced").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(round1, "$.questions")).isNotEmpty();

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"名称+数量，数量非负，验收为 qty=-1 拒绝\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specProduced").value(true))
                .andExpect(jsonPath("$.spec.valid").value(true));

        Integer clarifies = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_log WHERE issue_id = ? AND kind = 'clarify'",
                Integer.class, issueId);
        assertThat(clarifies).isEqualTo(2);
        String model = jdbc.queryForObject(
                "SELECT model FROM ai_task_log WHERE issue_id = ? LIMIT 1", String.class,
                issueId);
        assertThat(model).isEqualTo("fixture-clarify-v1");
    }

    // ===== FR-ISSUE-05：手工规格兜底 → 生成 → 标准导入激活 → IN_TESTING → USER CRUD =====
    @Test
    void manualSpecGeneratesInstallsAndAdvancesToTesting() throws Exception {
        String issueId = createIssue("手工生成", "手工规格兜底（模型不可用场景）");

        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MANUAL_SPEC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        String generateBody = generateAndAssertTesting(issueId);
        assertGeneratedPluginUsable(JsonPath.read(generateBody, "$.pluginId"));
        Integer generateLogs = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_log WHERE issue_id = ? AND kind = 'generate'"
                        + " AND output_valid = true", Integer.class, issueId);
        assertThat(generateLogs).isEqualTo(1);
    }

    private String generateAndAssertTesting(String issueId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/issues/" + issueId + "/generate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_TESTING"))
                .andReturn().getResponse().getContentAsString();
    }

    private void assertGeneratedPluginUsable(String pluginId) throws Exception {
        assertThat(pluginId).startsWith("gen.");
        // 生成的插件同权可用：菜单可见 + 普通用户 CRUD 生成实体
        assertThat(menuKeys(mockMvc, userBearer)).contains(pluginId + ".manualgen_item");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/manualgen_item")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"生成记录\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/manualgen_item?pageSize=10")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ===== fixture 端到端：clarify → approve → generate（AI 路径全链）=====
    @Test
    void fixtureEndToEndClarifyApproveGenerate() throws Exception {
        String issueId = createIssue("AI 全链", "澄清后直接生成");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"名称+数量\"}"))
                .andExpect(jsonPath("$.specProduced").value(true));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_TESTING"));
        assertThat(menuKeys(mockMvc, userBearer)).contains(
                jdbc.queryForObject("SELECT 'gen.i' || replace(replace(?, 'iss-', ''), '-', '')"
                        + " || '.clarify_item'", String.class, issueId));
    }

    // ===== 生成前置拒绝：无合法规格 / 非 APPROVED =====
    @Test
    void generateRejectedWithoutValidSpecOrApproval() throws Exception {
        String noSpec = createIssue("无规格生成", "拒绝");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + noSpec + "/generate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isBadRequest());

        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + noSpec + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MANUAL_SPEC))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + noSpec + "/generate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已批准")));
    }

    // ===== 生成失败转 DEV_FAILED（实体名被平台占用 → 注册冲突）=====
    @Test
    void generateFailureMarksIssueDevFailed() throws Exception {
        String occupied = "{\n\"schemaVersion\":1,\"summary\":\"占用实体\",\"entities\":[\n"
                + "{\"name\":\"occupied_item\",\"displayName\":\"占用\",\"fields\":[\n"
                + "{\"name\":\"name\",\"displayName\":\"名称\",\"fieldType\":\"text\"}]}],\n"
                + "\"acceptance\":[\"可查询\"]}";
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/meta/entities")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"occupied_item\",\"displayName\":\"平台占用\"}"))
                .andExpect(status().isOk());

        String issueId = createIssue("占用实体生成", "冲突转 DEV_FAILED");
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(occupied))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId)
                        .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEV_FAILED"));
        String reason = jdbc.queryForObject(
                "SELECT reason FROM issue_transition WHERE issue_id = ?"
                        + " AND to_status = 'DEV_FAILED'", String.class, issueId);
        assertThat(reason).contains("occupied_item").contains("禁止跨归属覆盖");
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_log WHERE issue_id = ? AND kind = 'generate'"
                        + " AND output_valid = false", Integer.class, issueId);
        assertThat(failed).isEqualTo(1);
    }

    // ===== clarify 权限：仅作者或开发者；generate 仅开发者 =====
    @Test
    void clarifyAndGenerateRoleMatrix() throws Exception {
        String issueId = createIssue("权限", "作者或开发者");
        String adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        // 开发者可澄清；管理员（非作者非开发者）不可
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", adminBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // 普通用户（作者）可澄清；生成仅开发者
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/clarify")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    // ===== 迭代回路（复审 P1-2）：反馈→修改规格→再生成，版本随 revision 递增不撞不可变 =====
    @Test
    void regenerateAfterSpecRevisionProducesNewVersion() throws Exception {
        String issueId = createIssue("迭代", "二次生成");
        saveManualSpec(issueId, iterSpec("iter_item"));
        approve(issueId);
        String first = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                                .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(first, "$.version"))
                .isEqualTo("0.1.1");

        // 反馈修复 → 回 APPROVED → 修改规格（新 revision）→ 再生成
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"FEEDBACK\",\"reason\":\"字段不足\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        saveManualSpec(issueId, iterSpec("iter_item2"));
        String second = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                                .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_TESTING"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) com.jayway.jsonpath.JsonPath.read(second, "$.version"))
                .isEqualTo("0.1.2");
    }

    private static String iterSpec(String entityName) {
        return "{\"schemaVersion\":1,\"summary\":\"迭代规格\",\"entities\":["
                + "{\"name\":\"" + entityName + "\",\"displayName\":\"迭代项\",\"fields\":["
                + "{\"name\":\"name\",\"displayName\":\"名称\",\"fieldType\":\"text\"}]}],"
                + "\"acceptance\":[\"可查询\"]}";
    }

    private void saveManualSpec(String issueId, String spec) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spec))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    private void approve(String issueId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());
    }

    private String createIssue(String title, String description) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }
}
