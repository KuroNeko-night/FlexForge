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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P04 breaking/additive 变更规则与审计/缓存验收（docs/09 P04）：
 * 非 draft 实体上语义变更（改名/改类型/改必填/改校验/改默认值）被 400 拒绝且带 breaking 提示；
 * 新增字段/视图 additive 放行；表现层（displayName/rendererId）随时可改；
 * 每次元数据写入产生 meta.* 审计行（actor=操作者用户名）；写后详情即时可见且 metaVersion 递增。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MetaRulesApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String TEXT_FIELD = "{\"name\":\"sku\",\"displayName\":\"SKU\","
            + "\"fieldType\":\"text\",\"required\":true,\"validation\":{\"minLength\":3,\"maxLength\":32}}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String developerBearer;
    private String userBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    /** 建实体 + 一个 text 字段，并按目标状态推进（draft/enabled/disabled）。 */
    private String preparedEntity(String name, String targetStatus) throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, name);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId, TEXT_FIELD, 200);
        if (!"draft".equals(targetStatus)) {
            MetaTestSupport.transition(mockMvc, developerBearer, entityId, "enabled", 200);
            if ("disabled".equals(targetStatus)) {
                MetaTestSupport.transition(mockMvc, developerBearer, entityId, "disabled", 200);
            }
        }
        return entityId;
    }

    private String fieldIdOf(String entityId) throws Exception {
        String detail = MetaTestSupport.getEntity(mockMvc, developerBearer, entityId, 200);
        return JsonPath.read(detail, "$.fields[0].id");
    }

    @Test
    void enabledEntityRejectsBreakingFieldChanges() throws Exception {
        String entityId = preparedEntity("meta_rules_breaking", "enabled");
        String fieldId = fieldIdOf(entityId);

        String rename = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"name\":\"sku_code\"}", 400);
        assertThat(rename).contains("breaking");
        String typeChange = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"fieldType\":\"integer\"}", 400);
        assertThat(typeChange).contains("breaking");
        MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"required\":false}", 400);
        MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"validation\":{\"minLength\":2,\"maxLength\":32}}", 400);
        MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"defaultValue\":\"abc\"}", 400);
    }

    @Test
    void enabledEntityRejectsEntityRenameButAllowsDisplayEdits() throws Exception {
        String entityId = preparedEntity("meta_rules_entity_rename", "enabled");
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/entities/" + entityId)
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed_entity\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/entities/" + entityId)
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"新显示名\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void disabledEntityAlsoRejectsBreakingChanges() throws Exception {
        String entityId = preparedEntity("meta_rules_disabled", "disabled");
        String fieldId = fieldIdOf(entityId);
        String response = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"fieldType\":\"integer\"}", 400);
        assertThat(response).contains("breaking");
    }

    @Test
    void additiveChangesAllowedOnEnabledEntity() throws Exception {
        String entityId = preparedEntity("meta_rules_additive", "enabled");
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"qty\",\"displayName\":\"数量\",\"fieldType\":\"integer\"}", 200);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/meta/entities/" + entityId + "/views")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewType\":\"list\",\"name\":\"列表\",\"columns\":[{\"field\":\"sku\"}]}"))
                .andExpect(status().isOk());

        String fieldId = fieldIdOf(entityId);
        String edited = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"displayName\":\"SKU 编码\",\"rendererId\":\"text.default\"}", 200);
        assertThat(JsonPath.read(edited, "$.displayName").toString()).isEqualTo("SKU 编码");
    }

    @Test
    void draftEntityAllowsRenameUntilViewReferencesTheField() throws Exception {
        String entityId = preparedEntity("meta_rules_draft_rename", "draft");
        String fieldId = fieldIdOf(entityId);

        MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"name\":\"sku_code\"}", 200);

        String viewBody = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/meta/entities/" + entityId + "/views")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewType\":\"list\",\"name\":\"列表\",\"columns\":[{\"field\":\"sku_code\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewId = JsonPath.read(viewBody, "$.id");

        String renamed = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"name\":\"sku_final\"}", 400);
        assertThat(renamed).contains("视图引用");

        // 被引用字段改名路径：先把视图引用清空，改名后再把引用指向新名
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/views/" + viewId)
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[]}"))
                .andExpect(status().isOk());
        MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"name\":\"sku_final\"}", 200);
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/views/" + viewId)
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"field\":\"sku_final\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidFieldTypeRendererAndRulesRejectedAtBoundary() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_rules_invalid");
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"bad_type\",\"displayName\":\"X\",\"fieldType\":\"string\"}", 400);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"bad_renderer\",\"displayName\":\"X\",\"fieldType\":\"text\","
                        + "\"rendererId\":\"custom.renderer\"}", 400);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"bad_rule\",\"displayName\":\"X\",\"fieldType\":\"text\","
                        + "\"validation\":{\"min\":1}}", 400);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"BadName\",\"displayName\":\"X\",\"fieldType\":\"text\"}", 400);
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"bad_default\",\"displayName\":\"X\",\"fieldType\":\"enum\","
                        + "\"validation\":{\"options\":[\"a\"]},\"defaultValue\":\"b\"}", 400);
        MetaTestSupport.getEntity(mockMvc, developerBearer, "meta-does-not-exist", 404);
    }

    @Test
    void metadataWritesAreAuditedWithActorUsername() throws Exception {
        String entityId = preparedEntity("meta_rules_audit", "enabled");
        List<String> actions = jdbc.queryForList(
                "SELECT action FROM sys_audit_event WHERE actor = ? AND action LIKE 'meta.%'"
                        + " ORDER BY occurred_at", String.class, AuthTestSupport.DEVELOPER_USERNAME);
        assertThat(actions).contains("meta.entity.create", "meta.field.create", "meta.entity.update");

        String objectIds = jdbc.queryForObject(
                "SELECT string_agg(DISTINCT object_id, ',') FROM sys_audit_event"
                        + " WHERE actor = ? AND action = 'meta.entity.create'", String.class,
                AuthTestSupport.DEVELOPER_USERNAME);
        assertThat(objectIds).contains(entityId);
    }

    @Test
    void writesInvalidateCacheAndBumpMetaVersionImmediately() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_rules_cache");
        String before = MetaTestSupport.getEntity(mockMvc, developerBearer, entityId, 200);
        long versionBefore = ((Number) JsonPath.read(before, "$.metaVersion")).longValue();
        assertThat((Integer) JsonPath.read(before, "$.fields.length()")).isEqualTo(0);

        MetaTestSupport.addField(mockMvc, developerBearer, entityId, TEXT_FIELD, 200);

        String after = MetaTestSupport.getEntity(mockMvc, developerBearer, entityId, 200);
        long versionAfter = ((Number) JsonPath.read(after, "$.metaVersion")).longValue();
        assertThat((Integer) JsonPath.read(after, "$.fields.length()")).isEqualTo(1);
        assertThat(versionAfter).isGreaterThan(versionBefore);
    }

    @Test
    void explicitJsonNullMeansUnchangedNotCleared() throws Exception {
        String entityId = preparedEntity("meta_rules_null_patch", "draft");
        String fieldId = fieldIdOf(entityId);

        String patched = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"displayName\":\"SKU 新名\",\"validation\":null,\"defaultValue\":null}", 200);
        assertThat(JsonPath.read(patched, "$.displayName").toString()).isEqualTo("SKU 新名");
        assertThat(JsonPath.read(patched, "$.validation.minLength").toString()).isEqualTo("3");
    }

    @Test
    void equalValueResendOnEnabledEntityIsNotBreaking() throws Exception {
        String entityId = preparedEntity("meta_rules_equal_resend", "enabled");
        String fieldId = fieldIdOf(entityId);

        MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"validation\":{\"maxLength\":32,\"minLength\":3}}", 200);
    }

    @Test
    void rendererMustMatchFieldType() throws Exception {
        String entityId = MetaTestSupport.createEntity(mockMvc, developerBearer, "meta_rules_renderer");
        MetaTestSupport.addField(mockMvc, developerBearer, entityId,
                "{\"name\":\"qty\",\"displayName\":\"数量\",\"fieldType\":\"integer\","
                        + "\"rendererId\":\"text.default\"}", 400);

        MetaTestSupport.addField(mockMvc, developerBearer, entityId, TEXT_FIELD, 200);
        String fieldId = fieldIdOf(entityId);
        String response = MetaTestSupport.patchField(mockMvc, developerBearer, fieldId,
                "{\"rendererId\":\"integer.default\"}", 400);
        assertThat(response).contains("renderer");
    }

    @Test
    void viewTypeImmutableAfterCreation() throws Exception {
        String entityId = preparedEntity("meta_rules_view_type", "draft");
        String viewBody = mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/meta/entities/" + entityId + "/views")
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewType\":\"list\",\"name\":\"列\",\"columns\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewId = JsonPath.read(viewBody, "$.id");

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/views/" + viewId)
                        .header("Authorization", developerBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewType\":\"form\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledEntityHiddenFromUser() throws Exception {
        String entityId = preparedEntity("meta_rules_disabled_hidden", "disabled");
        MetaTestSupport.getEntity(mockMvc, userBearer, entityId, 404);
        MetaTestSupport.getEntity(mockMvc, developerBearer, entityId, 200);
    }
}
