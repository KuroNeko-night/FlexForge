package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-E2E 五场景演示脚本（docs/02 §4 场景 A/B/B2/C/D、docs/09 P12 验收）：单脚本
 * 串联主链路并逐环节计时（论文/答辩耗时数据，对照 docs/00 §5 十分钟目标）。
 * 每轮运行前置条件：数据库已重置为"只执行平台迁移"的干净状态（由
 * AgentIssueE2eTest 负责 DROP SCHEMA + Flyway 重迁移 + 种子三角色）。
 */
final class E2eDemoScript {

    private static final String NAV_KEY = "example.inventory.items";

    /** 三角色 Bearer 令牌（每轮重置后重新登录获取）。 */
    record Actors(String admin, String developer, String user) {
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private final MockMvc mockMvc;
    private final JdbcTemplate jdbc;
    private final Actors actors;
    private final byte[] inventoryZip;
    private final int iteration;
    private final StringBuilder report = new StringBuilder();

    /** B 阶段停用后的 activationId，供 B2 过期激活断言复用。 */
    private String staleActivationId;

    E2eDemoScript(MockMvc mockMvc, JdbcTemplate jdbc, Actors actors, byte[] inventoryZip,
                  int iteration) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.actors = actors;
        this.inventoryZip = inventoryZip;
        this.iteration = iteration;
    }

    /** 五场景顺序执行；耗时行 `RB-E2E,iter,stage,ms`（CI 日志即论文证据）。 */
    void runAllScenarios() throws Exception {
        long started = System.nanoTime();
        timed("D.pre", this::assertCleanSkeleton);
        timed("A", this::scenarioDynamicEntity);
        timed("B", this::scenarioPluginLifecycle);
        timed("B2", this::scenarioFailureAndStale);
        timed("C", this::scenarioAgentIssue);
        timed("D.post", this::assertNoActivePlugins);
        report.append("RB-E2E,").append(iteration).append(",total,")
                .append((System.nanoTime() - started) / 1_000_000).append('\n');
    }

    String report() {
        return report.toString();
    }

    private void timed(String stage, Step step) throws Exception {
        long started = System.nanoTime();
        step.run();
        long ms = (System.nanoTime() - started) / 1_000_000;
        report.append("RB-E2E,").append(iteration).append(',').append(stage).append(',')
                .append(ms).append('\n');
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int count(String table, String column, String value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    // ===== 场景 D 前置：干净骨架无业务菜单/实体/插件（NFR-SKEL-01）=====
    private void assertCleanSkeleton() throws Exception {
        assertThat(menuKeys(mockMvc, actors.user()))
                .noneMatch(key -> key.startsWith("example.") || key.startsWith("gen."));
        assertThat(count("meta_entity")).isZero();
        assertThat(count("plugin_instance")).isZero();
        assertThat(count("issue")).isZero();
    }

    // ===== 场景 A：开发者建实体配四字段 → 启用 → 普通用户 CRUD =====
    private void scenarioDynamicEntity() throws Exception {
        String entityName = "demo_material";
        String entityId = MetaTestSupport.createEntity(mockMvc, actors.developer(), entityName);
        addField("{\"name\":\"name\",\"displayName\":\"名称\",\"fieldType\":\"text\","
                + "\"required\":true,\"position\":0}", entityId);
        addField("{\"name\":\"qty\",\"displayName\":\"数量\",\"fieldType\":\"integer\","
                + "\"validation\":{\"min\":0},\"position\":1}", entityId);
        addField("{\"name\":\"unit_price\",\"displayName\":\"单价\",\"fieldType\":\"decimal\","
                + "\"position\":2}", entityId);
        addField("{\"name\":\"status\",\"displayName\":\"状态\",\"fieldType\":\"enum\","
                + "\"validation\":{\"options\":[\"in_stock\",\"sold_out\"]},\"position\":3}",
                entityId);
        addView(entityId, "{\"viewType\":\"list\",\"name\":\"物料列表\",\"columns\":"
                + "[{\"field\":\"name\"},{\"field\":\"qty\",\"visible\":true}]}");
        addView(entityId, "{\"viewType\":\"form\",\"name\":\"物料表单\",\"columns\":"
                + "[{\"field\":\"name\"},{\"field\":\"status\"}]}");
        MetaTestSupport.transition(mockMvc, actors.developer(), entityId, "enabled", 200);
        userSeesAndEditsMaterial(entityName);
    }

    private void addField(String fieldJson, String entityId) throws Exception {
        MetaTestSupport.addField(mockMvc, actors.developer(), entityId, fieldJson, 200);
    }

    private void addView(String entityId, String viewJson) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/meta/entities/" + entityId + "/views")
                        .header("Authorization", actors.developer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewJson))
                .andExpect(status().isOk());
    }
    private void userSeesAndEditsMaterial(String entityName) throws Exception {
        String entityList = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/meta/entities?page=1&pageSize=100")
                                .header("Authorization", actors.user()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(entityList, "$.items[*].name");
        assertThat(names).containsExactly(entityName);        String created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/" + entityName)
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M6 螺栓\",\"qty\":10,\"unit_price\":0.5,"
                                + "\"status\":\"in_stock\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/data/" + entityName + "/" + id)
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qty\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qty").value(20));
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/" + entityName + "?pageSize=10")
                        .header("Authorization", actors.user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ===== 场景 B：example-inventory 安装→使用→停用→再启用→卸载（FR-DEMO-03）=====
    private void scenarioPluginLifecycle() throws Exception {
        String versionId = installInventoryPlugin();
        useInventoryAsUser();
        stopAssertRemovedAndStale(versionId);
        reEnableAssertRestored();
        uninstallAssertClean();
    }

    private String installInventoryPlugin() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/validate")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                inventoryZip))
                        .header("Authorization", actors.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                        inventoryZip))
                                .header("Authorization", actors.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");
        String activationId = JsonPath.read(PluginPackageTestSupport.activate(
                mockMvc, actors.admin(), versionId), "$.id");
        assertThat(menuKeys(mockMvc, actors.user())).contains(NAV_KEY);
        return activationId;
    }

    private void useInventoryAsUser() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/inventory_item")
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"KEEP-1\",\"name\":\"保留数据\",\"qty\":3,"
                                + "\"status\":\"在库\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/inventory_item")
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"BAD\",\"name\":\"坏数据\",\"qty\":-1,"
                                + "\"status\":\"在库\"}"))
                .andExpect(status().isBadRequest());
    }

    private void stopAssertRemovedAndStale(String activationId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", actors.admin()))
                .andExpect(status().isOk());
        assertThat(menuKeys(mockMvc, actors.user())).doesNotContain(NAV_KEY);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/inventory_item")
                        .header("Authorization", actors.user()))
                .andExpect(status().isNotFound());
        // 场景 B2 前半：过期 activationId 的注册查询被拒绝且不影响当前状态
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/activations/" + activationId + "/registrations")
                        .header("Authorization", actors.admin()))
                .andExpect(status().isConflict());
        staleActivationId = activationId;
    }

    private void reEnableAssertRestored() throws Exception {
        String versionId = jdbc.queryForObject(
                "SELECT id FROM plugin_version WHERE plugin_id = 'example.inventory'",
                String.class);
        PluginPackageTestSupport.activate(mockMvc, actors.admin(), versionId);
        assertThat(menuKeys(mockMvc, actors.user())).contains(NAV_KEY);
        String listBody = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/data/inventory_item?pageSize=50")
                                .header("Authorization", actors.user()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> skus = JsonPath.read(listBody, "$.items[*].data.sku");
        assertThat(skus).contains("KEEP-1");
    }

    private void uninstallAssertClean() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/example.inventory")
                        .header("Authorization", actors.admin()))
                .andExpect(status().isOk());
        assertThat(menuKeys(mockMvc, actors.user())).doesNotContain(NAV_KEY);
        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration pr JOIN plugin_activation pa"
                        + " ON pr.activation_id = pa.id WHERE pa.plugin_id = 'example.inventory'",
                Integer.class);
        assertThat(registrations).isZero();
        Map<String, Object> entity = jdbc.queryForMap(
                "SELECT status FROM meta_entity WHERE name = 'inventory_item'");
        assertThat(entity.get("status")).isEqualTo("disabled");
        assertThat(count("sys_audit_event", "action", "plugin.uninstall")).isEqualTo(1);
    }
    // ===== 场景 B2 后半：依赖未激活时安装失败，阶段明确、注册回滚、可清理重试 =====
    private void scenarioFailureAndStale() throws Exception {
        PluginPackageTestSupport.importVersion(mockMvc, actors.admin(), "e2e.dep.base", "1.0.0",
                PluginPackageTestSupport.defaultBody("e2e_dep_base", "e2e_dep_base"));
        String versionId = PluginPackageTestSupport.importVersion(mockMvc, actors.admin(),
                "e2e.dep", "1.0.0", new PluginPackageTestSupport.PackageBody(
                        "[\"migrations/V001__init.sql\"]",
                        PluginPackageTestSupport.entitiesJson("e2e_dep_item"),
                        PluginPackageTestSupport.migrationSql("e2e_dep_item"),
                        "[{\"pluginId\":\"e2e.dep.base\",\"versionRange\":\"^1.0.0\"}]"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", actors.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("dependency_missing"));
        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT status, stage FROM plugin_activation WHERE plugin_version_id = ?",
                versionId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("stage")).isEqualTo("DEPENDENCY_CHECK");
        assertThat(count("meta_entity", "name", "e2e_dep_item")).isZero();
        // 失败可恢复：卸载清理失败实例与依赖，系统回到可操作状态
        for (String pluginId : List.of("e2e.dep", "e2e.dep.base")) {
            mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/" + pluginId)
                            .header("Authorization", actors.admin()))
                    .andExpect(status().isOk());
        }
    }

    // ===== 场景 C：Issue → AI 澄清 → 人工批准 → 生成安装 → 测试 → 完成 =====
    private void scenarioAgentIssue() throws Exception {
        String issueId = createIssueAndClarify();
        assertGenerateRequiresApproval(issueId);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", actors.developer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"APPROVED\"}"))
                .andExpect(status().isOk());
        String generateBody = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                                .header("Authorization", actors.developer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("IN_TESTING"))
                .andReturn().getResponse().getContentAsString();
        String pluginId = JsonPath.read(generateBody, "$.pluginId");
        assertThat(JsonPath.<String>read(generateBody, "$.version")).isEqualTo("0.1.1");
        userUsesGeneratedEntity(pluginId);
        assertCrossTraceability(issueId, pluginId);
        completeAndCleanupIssue(issueId, pluginId);
    }

    private String createIssueAndClarify() throws Exception {
        String issueId = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"管理物料库存\",\"description\":\"库存数量不能为负\","
                                + "\"labels\":[\"inventory\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String issueIdValue = JsonPath.read(issueId, "$.id");
        String round1 = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/issues/" + issueIdValue + "/clarify")
                                .header("Authorization", actors.user())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specProduced").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(round1, "$.questions")).isNotEmpty();
        mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/issues/" + issueIdValue + "/clarify")
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"名称+数量，数量非负，验收为 qty=-1 拒绝\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specProduced").value(true));
        return issueIdValue;
    }

    /** 验收四：人工批准之前 generate 被拒且不产生任何插件实例。 */
    private void assertGenerateRequiresApproval(String issueId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/generate")
                        .header("Authorization", actors.developer()))
                .andExpect(status().is4xxClientError());
        Integer generated = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_instance WHERE plugin_id LIKE 'gen.%'",
                Integer.class);
        assertThat(generated).isZero();
    }

    private void userUsesGeneratedEntity(String pluginId) throws Exception {
        assertThat(menuKeys(mockMvc, actors.user())).contains(pluginId + ".clarify_item");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/clarify_item")
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"生成记录\",\"qty\":5}"))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/clarify_item")
                        .header("Authorization", actors.user())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"坏数据\",\"qty\":-1}"))
                .andExpect(status().isBadRequest());
    }

    /** 验收三：Issue ↔ 插件版本 ↔ activation ↔ 审计互相可追踪。 */
    private void assertCrossTraceability(String issueId, String pluginId) throws Exception {
        assertThat(count("ai_task_log", "issue_id", issueId)).isEqualTo(3);
        Integer transitions = jdbc.queryForObject(
                "SELECT count(*) FROM issue_transition WHERE issue_id = ?", Integer.class,
                issueId);
        assertThat(transitions).isEqualTo(2);
        assertThat(count("sys_audit_event", "action", "issue.generate")).isEqualTo(1);
        assertThat(count("sys_audit_event", "action", "plugin.activate")).isEqualTo(3);
        String inventory = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/plugins/inventory")
                                .header("Authorization", actors.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(inventory, "$[*].pluginId");
        assertThat(ids).contains(pluginId, "example.inventory", "e2e.dep");
    }

    private void completeAndCleanupIssue(String issueId, String pluginId) throws Exception {
        transitionIssue(issueId, "TESTED");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/issues/" + issueId)
                        .header("Authorization", actors.developer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TESTED"));
        transitionIssue(issueId, "DONE");
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/" + pluginId)
                        .header("Authorization", actors.admin()))
                .andExpect(status().isOk());
        assertThat(menuKeys(mockMvc, actors.user()))
                .noneMatch(key -> key.startsWith("gen."));
    }
    private void transitionIssue(String issueId, String to) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/issues/" + issueId + "/transition")
                        .header("Authorization", actors.developer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"" + to + "\"}"))
                .andExpect(status().isOk());
    }

    // ===== 场景 D 后置：全链结束无 ACTIVE 激活、无业务菜单残留 =====
    private void assertNoActivePlugins() throws Exception {
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_activation WHERE status IN ('STARTING','ACTIVE')",
                Integer.class);
        assertThat(active).isZero();
        assertThat(menuKeys(mockMvc, actors.user()))
                .noneMatch(key -> key.startsWith("example.") || key.startsWith("gen."));
    }
}
