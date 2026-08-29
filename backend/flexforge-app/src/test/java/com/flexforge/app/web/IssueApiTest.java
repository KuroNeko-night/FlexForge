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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-ISSUE（docs/11：状态机合法/非法迁移、规格 Schema 版本审计、退回与关闭原因）。
 * 用例间共享容器且按声明顺序依赖前置状态（JUnit 默认线程内确定性顺序），
 * 每个用例自建 Issue 隔离主体链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class IssueApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String VALID_SPEC = """
            {"schemaVersion":1,"summary":"库存管理","entities":[{"name":"inv_spec_item",
            "displayName":"库存项","fields":[{"name":"sku","displayName":"编码",
            "fieldType":"text","required":true},{"name":"qty","displayName":"数量",
            "fieldType":"integer","validation":{"min":0}}]}],
            "permissions":["inv.read"],"acceptance":["qty=-1 拒绝"]}
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

    // ===== FR-ISSUE-01：创建/评论/标签/指派（普通用户即可）+ 审计 =====
    @Test
    void userCreatesCommentsLabelsAndAssignsIssue() throws Exception {
        String issueId = createIssue(userBearer, "库存导入需求", "支持 Excel 批量导入",
                "inventory", "import");
        assertThat(issueId).startsWith("iss-");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.labels.length()").value(2));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/comments")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"补充：需要失败行报告\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId + "/comments")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/issues/" + issueId + "/labels")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"labels\":[\"p0\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels.length()").value(1));
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/issues/" + issueId + "/assignee")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"test-developer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedTo").value("test-developer"));

        assertAudit(issueId, "issue.create");
        assertAudit(issueId, "issue.comment");
    }

    // ===== 非法创建输入 =====
    @Test
    void invalidIssuePayloadRejected() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    // ===== 规格门：缺规格/invalid 规格 不能批准；valid 规格放行（验收 2）=====
    @Test
    void approvalRequiresValidLatestSpec() throws Exception {
        String issueId = createIssue(userBearer, "审批门", "规格门校验", "gate");

        // 无规格 → 批准拒绝
        transition(developerBearer, issueId, "APPROVED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_transition"));

        // invalid 规格（缺 acceptance）→ 仍拒绝
        saveSpec(developerBearer, issueId,
                "{\"schemaVersion\":1,\"summary\":\"不完整\",\"entities\":[],\"acceptance\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        transition(developerBearer, issueId, "APPROVED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_transition"));

        // valid 规格 → 批准通过
        saveSpec(developerBearer, issueId, VALID_SPEC)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        transition(developerBearer, issueId, "APPROVED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    // ===== 状态机全链 + 退回/关闭原因 + 迁移记录与审计（验收 1/3）=====
    @Test
    void fullLifecycleWithReasonsTransitionsAndAudit() throws Exception {
        String issueId = createIssue(userBearer, "生命周期", "全链迁移", "flow");
        saveSpec(developerBearer, issueId, VALID_SPEC).andExpect(status().isOk());

        // 退回须原因
        transition(developerBearer, issueId, "RETURNED", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
        transition(developerBearer, issueId, "RETURNED", "需求不清晰")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETURNED"));
        // 修改后重新提交 → 批准 → 待测试 → 测试通过 → 关闭（带原因）
        transition(developerBearer, issueId, "SUBMITTED", null)
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        transition(developerBearer, issueId, "APPROVED", null)
                .andExpect(jsonPath("$.status").value("APPROVED"));
        transition(developerBearer, issueId, "IN_TESTING", null)
                .andExpect(jsonPath("$.status").value("IN_TESTING"));
        transition(developerBearer, issueId, "TESTED", null)
                .andExpect(jsonPath("$.status").value("TESTED"));
        transition(developerBearer, issueId, "CLOSED", "重复需求")
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // 终态不可再迁移
        transition(developerBearer, issueId, "DONE", null)
                .andExpect(status().isBadRequest());

        // 迁移记录（操作者/原因/时间）与审计
        Integer transitions = jdbc.queryForObject(
                "SELECT count(*) FROM issue_transition WHERE issue_id = ?", Integer.class,
                issueId);
        assertThat(transitions).isEqualTo(6);
        String returnReason = jdbc.queryForObject(
                "SELECT reason FROM issue_transition WHERE issue_id = ? AND to_status = 'RETURNED'",
                String.class, issueId);
        assertThat(returnReason).isEqualTo("需求不清晰");
        assertAudit(issueId, "issue.transition");
    }

    // ===== 非法迁移（越级/倒退）拒绝 =====
    @Test
    void illegalTransitionsRejected() throws Exception {
        String issueId = createIssue(userBearer, "非法迁移", "越级与倒退", "illegal");
        transition(developerBearer, issueId, "DONE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_transition"));
        transition(developerBearer, issueId, "IN_TESTING", null)
                .andExpect(status().isBadRequest());
        transition(developerBearer, issueId, "RETURNED", "先退回")
                .andExpect(status().isOk());
        transition(developerBearer, issueId, "APPROVED", null)
                .andExpect(status().isBadRequest());
    }

    // ===== 规格版本审计（验收 3）：多版本递增 + valid/错误快照留存 =====
    @Test
    void specRevisionsAreVersionedAndAudited() throws Exception {
        String issueId = createIssue(userBearer, "规格版本", "多版本留存", "spec");
        saveSpec(developerBearer, issueId,
                "{\"schemaVersion\":1,\"summary\":\"v1 不完整\",\"entities\":[]}")
                .andExpect(status().isOk());
        saveSpec(developerBearer, issueId, VALID_SPEC).andExpect(status().isOk());

        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/issues/" + issueId + "/spec/revisions")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].revision").value(2))
                .andExpect(jsonPath("$[0].valid").value(true))
                .andExpect(jsonPath("$[1].valid").value(false));

        // 最新版读取 + 错误快照留存
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        String errors = jdbc.queryForObject(
                "SELECT validation_errors::text FROM requirement_spec"
                        + " WHERE issue_id = ? AND revision = 1", String.class, issueId);
        assertThat(errors).contains("acceptance");
        assertAudit(issueId, "issue.spec.update");
    }

    // ===== 预览（验收 4）：valid 规格派生资源；无规格 400；开发者专属 =====
    @Test
    void previewDerivesPluginResourcesForDeveloper() throws Exception {
        String issueId = createIssue(userBearer, "预览", "资源派生", "preview");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId + "/preview")
                        .header("Authorization", developerBearer))
                .andExpect(status().isNotFound());

        saveSpec(developerBearer, issueId, VALID_SPEC).andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId + "/preview")
                        .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.resources['plugin.json']").exists())
                .andExpect(jsonPath("$.resources['metadata/entities/inv_spec_item.json']")
                        .exists());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId + "/preview")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    // ===== 角色矩阵：迁移/规格写为开发者职责 =====
    @Test
    void workflowActionsRequireDeveloperRole() throws Exception {
        String issueId = createIssue(userBearer, "权限", "角色矩阵", "rbac");
        transition(userBearer, issueId, "APPROVED", null).andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_SPEC))
                .andExpect(status().isForbidden());
    }

    private String createIssue(String bearer, String title, String description,
                               String... labels) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description
                                + "\",\"labels\":[\"" + String.join("\",\"", labels) + "\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transitionBody(
            String issueId, String to, String reason) {
        String body = reason == null ? "{\"to\":\"" + to + "\"}"
                : "{\"to\":\"" + to + "\",\"reason\":\"" + reason + "\"}";
        return MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private org.springframework.test.web.servlet.ResultActions transition(String bearer,
            String issueId, String to, String reason) throws Exception {
        return mockMvc.perform(transitionBody(issueId, to, reason)
                .header("Authorization", bearer));
    }

    private org.springframework.test.web.servlet.ResultActions saveSpec(String bearer,
            String issueId, String spec) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/issues/" + issueId + "/spec")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(spec));
    }

    private void assertAudit(String issueId, String action) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = ? AND object_id LIKE ?",
                Integer.class, action, issueId + "%");
        assertThat(count).isPositive();
    }
}
