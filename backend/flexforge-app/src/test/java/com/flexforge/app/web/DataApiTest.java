package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * P05 验收（FR-META-04/05、NFR-SEC-02、RB-DATA）：动态 CRUD 全路径、
 * 字段校验与业务规则（qty≥0）、注入样例拒绝、过滤/排序/分页白名单、
 * 删除物理删除 + 审计、未启用实体 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String ENTITY = "data_api_item";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String userBearer;
    private String developerBearer;

    @BeforeAll
    void seedEntity() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        String developer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        String entityId = MetaTestSupport.createEntity(mockMvc, developer, ENTITY);
        MetaTestSupport.addField(mockMvc, developer, entityId,
                "{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\",\"required\":true,"
                        + "\"validation\":{\"minLength\":3,\"maxLength\":32}}", 200);
        MetaTestSupport.addField(mockMvc, developer, entityId,
                "{\"name\":\"qty\",\"displayName\":\"数量\",\"fieldType\":\"integer\",\"required\":true,"
                        + "\"validation\":{\"min\":0,\"max\":10000}}", 200);
        MetaTestSupport.addField(mockMvc, developer, entityId,
                "{\"name\":\"unit_price\",\"displayName\":\"单价\",\"fieldType\":\"decimal\","
                        + "\"validation\":{\"min\":0}}", 200);
        MetaTestSupport.addField(mockMvc, developer, entityId,
                "{\"name\":\"status\",\"displayName\":\"状态\",\"fieldType\":\"enum\","
                        + "\"validation\":{\"options\":[\"in_stock\",\"sold_out\"]},"
                        + "\"defaultValue\":\"in_stock\"}", 200);
        MetaTestSupport.transition(mockMvc, developer, entityId, "enabled", 200);
    }

    @BeforeEach
    void login() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
    }

    private String createRecord(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/" + ENTITY)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void fullCrudLifecycleWithDefaults() throws Exception {
        String created = createRecord(
                "{\"sku\":\"SKU-100\",\"qty\":5,\"unit_price\":9.5}");
        String id = JsonPath.read(created, "$.id");
        assertThat(JsonPath.read(created, "$.data.status").toString()).isEqualTo("in_stock");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY + "/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qty").value(5));

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/data/" + ENTITY + "/" + id)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":7,\"unit_price\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qty").value(7))
                .andExpect(jsonPath("$.data.unit_price").doesNotExist());

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/data/" + ENTITY + "/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY + "/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM data_record WHERE id = ?", Integer.class, id);
        assertThat(remaining).isZero();
    }

    @Test
    void invalidRecordsRejectedAtBoundary() throws Exception {
        postRecordExpecting400("{\"qty\":1}", "必填");
        postRecordExpecting400("{\"sku\":\"SKU-1\",\"qty\":-1}", "min");
        postRecordExpecting400("{\"sku\":\"SKU-1\",\"qty\":\"many\"}", "整数");
        postRecordExpecting400("{\"sku\":\"S\",\"qty\":1}", "minLength");
        postRecordExpecting400("{\"sku\":\"SKU-1\",\"qty\":1,\"status\":\"unknown\"}", "options");
        postRecordExpecting400("{\"sku\":\"SKU-1\",\"qty\":1,\"ghost_column\":true}", "未定义字段");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/" + ENTITY)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyEnabledEntitiesAccessible() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/no_such_entity")
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());

        String draft = MetaTestSupport.createEntity(mockMvc, developerBearer, "data_api_draft");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/data_api_draft")
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());

        MetaTestSupport.transition(mockMvc, developerBearer, draft, "enabled", 200);
        MetaTestSupport.transition(mockMvc, developerBearer, draft, "disabled", 200);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/data_api_draft")
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersAndSortingWorkOnWhitelistedFields() throws Exception {
        createRecord("{\"sku\":\"alpha-one\",\"qty\":1}");
        createRecord("{\"sku\":\"beta-two\",\"qty\":9}");
        createRecord("{\"sku\":\"alpha-three\",\"qty\":5}");

        String filtered = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("sku.contains", "ALPHA")
                        .queryParam("qty.gte", "2")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(filtered, "$.items.length()")).isEqualTo(1);
        assertThat(JsonPath.read(filtered, "$.items[0].data.sku").toString())
                .isEqualTo("alpha-three");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("sortBy", "qty").queryParam("direction", "DESC")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].data.qty").value(9));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("sortBy", "qty").queryParam("direction", "ASC")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].data.qty").value(1));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("sortBy", "createdAt")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk());
    }

    @Test
    void queryWhitelistsRejectInjectionShapedInputs() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("sortBy", "password")
                        .header("Authorization", userBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("sku;drop table data_record;--.contains", "x")
                        .header("Authorization", userBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("qty.contains", "5")
                        .header("Authorization", userBearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY)
                        .queryParam("pageSize", "201")
                        .header("Authorization", userBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("200")));
    }

    @Test
    void recordIsolationBetweenEntities() throws Exception {
        String other = MetaTestSupport.createEntity(mockMvc, developerBearer, "data_api_other");
        MetaTestSupport.addField(mockMvc, developerBearer, other,
                "{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\"}", 200);
        MetaTestSupport.transition(mockMvc, developerBearer, other, "enabled", 200);

        String created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/data_api_other")
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"other-sku\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String otherId = JsonPath.read(created, "$.id");

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY + "/" + otherId)
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());
    }

    @Test
    void dataWritesAreAudited() throws Exception {
        String created = createRecord("{\"sku\":\"audit-sku\",\"qty\":3}");
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/data/" + ENTITY + "/" + id)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":4}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/data/" + ENTITY + "/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk());

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM sys_audit_event WHERE actor = ? AND object_id = ?"
                        + " ORDER BY occurred_at", String.class,
                AuthTestSupport.USER_USERNAME, id);
        assertThat(actions).containsExactly("data.record.create", "data.record.update",
                "data.record.delete");
    }

    @Test
    void dataApiRequiresAuthentication() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + ENTITY))
                .andExpect(status().isUnauthorized());
    }

    private void postRecordExpecting400(String body, String messagePart) throws Exception {
        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/" + ENTITY)
                        .header("Authorization", userBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains(messagePart);
    }
}
