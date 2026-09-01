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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P04 验收（FR-META-01..03/05）：六类字段全量配置 + 默认 renderer 解析、
 * 角色矩阵（写=DEVELOPER、读=USER 仅 enabled）、实体状态迁移合法性、
 * 视图配置白名单、无令牌 401（docs/09 P04 验收标准）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MetaApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String developerBearer;
    private String userBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = bearer(AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        developerBearer = bearer(AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        userBearer = bearer(AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    private String bearer(String username, String password) throws Exception {
        return "Bearer " + AuthTestSupport.loginToken(mockMvc, username, password);
    }

    @Test
    void developerCreatesEntityWithSixFieldTypesAndViews() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_full_item");

        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\",\"required\":true,"
                        + "\"validation\":{\"minLength\":3,\"maxLength\":32}}", 200);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"qty\",\"displayName\":\"数量\",\"fieldType\":\"integer\","
                        + "\"validation\":{\"min\":0,\"max\":10000}}", 200);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"unit_price\",\"displayName\":\"单价\",\"fieldType\":\"decimal\","
                        + "\"validation\":{\"min\":0}}", 200);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"expiry_date\",\"displayName\":\"到期日\",\"fieldType\":\"date\","
                        + "\"defaultValue\":\"2027-01-01\"}", 200);
        String statusField = MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"status\",\"displayName\":\"状态\",\"fieldType\":\"enum\","
                        + "\"validation\":{\"options\":[\"in_stock\",\"sold_out\"]},"
                        + "\"defaultValue\":\"in_stock\"}", 200);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"archived\",\"displayName\":\"归档\",\"fieldType\":\"boolean\","
                        + "\"defaultValue\":false}", 200);

        addViewExpect(entityId, "{\"viewType\":\"list\",\"name\":\"库存列表\",\"columns\":"
                + "[{\"field\":\"sku\"},{\"field\":\"qty\",\"visible\":true}],"
                + "\"filters\":[{\"field\":\"sku\",\"operator\":\"contains\"},"
                + "{\"field\":\"qty\",\"operator\":\"gte\"}]}", 200);
        addViewExpect(entityId, "{\"viewType\":\"form\",\"name\":\"库存表单\",\"columns\":"
                + "[{\"field\":\"sku\"},{\"field\":\"status\"}]}", 200);

        String detail = MetaTestSupport.getEntity(mockMvc, developerBearer, entityId, 200);
        assertThat((Integer) JsonPath.read(detail, "$.fields.length()")).isEqualTo(6);
        assertThat((Integer) JsonPath.read(detail, "$.views.length()")).isEqualTo(2);
        assertThat(JsonPath.read(detail, "$.status").toString()).isEqualTo("draft");
        assertThat(JsonPath.read(detail, "$.fields[0].rendererId").toString()).isEqualTo("text.default");
        assertThat(JsonPath.read(detail, "$.fields[4].rendererId").toString()).isEqualTo("enum.default");
        assertThat(JsonPath.read(statusField, "$.rendererId").toString()).isEqualTo("enum.default");
        assertThat(JsonPath.read(statusField, "$.defaultValue").toString()).isEqualTo("in_stock");
    }

    @Test
    void onlyDeveloperMayWriteMeta() throws Exception {
        String entityJson = "{\"name\":\"meta_api_role_item\",\"displayName\":\"角色矩阵\"}";
        mockMvc.perform(postEntities(userBearer, entityJson)).andExpect(status().isForbidden());
        mockMvc.perform(postEntities(adminBearer, entityJson)).andExpect(status().isForbidden());

        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_role_item");
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/entities/" + entityId)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"越权改名\"}"))
                .andExpect(status().isForbidden());
        MetaTestSupport.addField(mockMvc, userBearer, entityId,
                "{\"name\":\"x\",\"displayName\":\"X\",\"fieldType\":\"text\"}", 403);
    }

    @Test
    void userSeesOnlyEnabledEntities() throws Exception {
        String draft = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_draft_only");
        String enabled = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_to_enable");
        MetaTestSupport.transition(mockMvc, developerBearer, enabled, "enabled", 200);

        String userList = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities")
                        .header("Authorization", userBearer)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(userList).contains("meta_api_to_enable");
        assertThat(userList).doesNotContain("meta_api_draft_only");

        MetaTestSupport.getEntity(mockMvc, userBearer, draft, 404);
        MetaTestSupport.getEntity(mockMvc, userBearer, enabled, 200);
        MetaTestSupport.getEntity(mockMvc, developerBearer, draft, 200);
        MetaTestSupport.getEntity(mockMvc, adminBearer, draft, 200);
    }

    @Test
    void entityLifecycleFollowsLegalTransitions() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_lifecycle");
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "disabled", 400);
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "enabled", 200);
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "draft", 400);
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "disabled", 200);
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "enabled", 200);
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "bogus", 400);
    }

    @Test
    void duplicateEntityNameAndFieldPerEntityRejected() throws Exception {
        MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_dup_item");
        mockMvc.perform(postEntities(developerBearer,
                        "{\"name\":\"meta_api_dup_item\",\"displayName\":\"重复\"}"))
                .andExpect(status().isBadRequest());

        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_dup_field");
        String fieldJson = "{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\"}";
        MetaTestSupport.addField(mockMvc, developerBearer, entityId, fieldJson, 200);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId, fieldJson, 400);
    }

    @Test
    void viewConfigurationWhitelistEnforced() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_view_rules");
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\"}", 200);

        addViewExpect(entityId, "{\"viewType\":\"list\",\"name\":\"列\",\"columns\":"
                + "[{\"field\":\"ghost\"}]}", 400);
        addViewExpect(entityId, "{\"viewType\":\"form\",\"name\":\"表单\",\"columns\":[{\"field\":\"sku\"}],"
                + "\"filters\":[{\"field\":\"sku\",\"operator\":\"contains\"}]}", 400);
        addViewExpect(entityId, "{\"viewType\":\"list\",\"name\":\"列\",\"columns\":[{\"field\":\"sku\"}],"
                + "\"filters\":[{\"field\":\"sku\",\"operator\":\"regex\"}]}", 400);
        addViewExpect(entityId, "{\"viewType\":\"bogus\",\"name\":\"列\",\"columns\":[]}", 400);
        addViewExpect(entityId, "{\"viewType\":\"list\",\"name\":\"列\",\"columns\":[{\"field\":\"sku\"}]}", 200);
        addViewExpect(entityId, "{\"viewType\":\"list\",\"name\":\"列二\",\"columns\":[]}", 400);
    }

    @Test
    void kanbanViewSameDisciplineAsListAndForm() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_kanban");
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"stage\",\"displayName\":\"阶段\",\"fieldType\":\"enum\","
                        + "\"validation\":{\"options\":[\"待办\",\"已完成\"]}}", 200);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"title\",\"displayName\":\"标题\",\"fieldType\":\"text\"}", 200);

        // groupBy 缺失 / 指向非 enum 字段：与插件路径同口径拒绝（P17 验收）
        addViewExpect(entityId, "{\"viewType\":\"kanban\",\"name\":\"看板\",\"columns\":[]}", 400);
        addViewExpect(entityId, "{\"viewType\":\"kanban\",\"name\":\"看板\",\"groupBy\":\"title\","
                + "\"columns\":[]}", 400);
        // 合法创建：groupBy 下发；list 误传 groupBy 归 null（对齐插件路径，审查 P2-1）
        addViewExpect(entityId, "{\"viewType\":\"kanban\",\"name\":\"看板\",\"groupBy\":\"stage\","
                + "\"columns\":[{\"field\":\"title\"}]}", 200);
        addViewExpect(entityId, "{\"viewType\":\"list\",\"name\":\"列\","
                + "\"columns\":[{\"field\":\"title\"}],\"groupBy\":\"stage\"}", 200);
        String detail = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/meta/entities/" + entityId)
                                .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> kanbanGroup = JsonPath.read(detail,
                "$.views[?(@.viewType == 'kanban')].groupBy");
        assertThat(kanbanGroup).containsExactly("stage");
        List<String> listGroup = JsonPath.read(detail,
                "$.views[?(@.viewType == 'list')].groupBy");
        assertThat(listGroup).hasSize(1).first().isNull();
    }

    @Test
    void entityDefinitionResolvableByNameForDynamicPages() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_api_by_name");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/meta_api_by_name")
                        .header("Authorization", developerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(entityId))
                .andExpect(jsonPath("$.name").value("meta_api_by_name"))
                .andExpect(jsonPath("$.fields.length()").value(0));

        // USER 动态页主路径：enabled 实体 200，draft 一律 404
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "enabled", 200);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/meta_api_by_name")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk());
        MetaTestSupport.transition(mockMvc, developerBearer, entityId, "disabled", 200);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/meta_api_by_name")
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/ghost_entity")
                        .header("Authorization", developerBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void metaApiRequiresAuthentication() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postEntities(
            String bearer, String body) {
        return MockMvcRequestBuilders.post("/api/v1/meta/entities")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private void addViewExpect(String entityId, String body, int expectedStatus) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/meta/entities/" + entityId + "/views")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }
}
