package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static com.flexforge.app.web.PluginPackageTestSupport.importAndActivate;
import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P21 插件预设验收（FR-PLUGIN-12）：保存当前启用集合（含版本）→ 应用收敛
 * （停用预设外→按预设切换/激活）逐项上报；失败路径（引用不存在版本→该项
 * failed 其余成功/非法名与重名 400/非 ADMIN 403/删除后 404）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PluginPresetApiTest {

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

    @Test
    void presetSaveApplyRestoresEnabledSet() throws Exception {
        importAndActivate(mockMvc, adminBearer, "preset.a", "0.1.0");
        importAndActivate(mockMvc, adminBearer, "preset.b", "0.1.0");
        String presetId = savePreset("生产演示");
        assertThat(presetId).isNotBlank();
        // 断开场景：停用 b、启用预设外的 c
        stopByPluginId("preset.b");
        importAndActivate(mockMvc, adminBearer, "preset.c", "0.1.0");

        // 用例间共享库（方法次序不保证）：断言取"包含"口径——apply 收敛语义保证
        // 预设内全部启用、预设外全部停用，与执行前库内其它插件状态无关
        String result = applyPreset(presetId);
        assertThat((List<String>) JsonPath.read(result, "$.activated"))
                .contains("preset.a", "preset.b");
        assertThat((List<String>) JsonPath.read(result, "$.stopped")).contains("preset.c");
        assertThat((List<?>) JsonPath.read(result, "$.failed")).isEmpty();
        // 用户可见效果（FR-PLUGIN-05）：预设内菜单回归、预设外撤销
        assertThat(menuKeys(mockMvc, userBearer)).contains("preset.a.items", "preset.b.items")
                .doesNotContain("preset.c.items");
        // 应用编排留审计
        Integer audited = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'plugin.preset.apply'",
                Integer.class);
        assertThat(audited).isGreaterThanOrEqualTo(1);
    }

    @Test
    void presetApplyReportsMissingVersionFailure() throws Exception {
        importAndActivate(mockMvc, adminBearer, "preset.d", "0.1.0");
        String presetId = savePreset("缺版本场景");
        // 数据损坏方向：payload 追加指向不存在版本的条目（预设外直接改库）
        jdbc.update("UPDATE plugin_preset SET payload = payload || "
                + "'[{\"pluginId\":\"preset.ghost\",\"versionId\":\"ghost-v\",\"version\":\"9.9.9\"}]'"
                + "::jsonb WHERE id = ?", presetId);

        String result = applyPreset(presetId);
        assertThat((List<String>) JsonPath.read(result, "$.activated")).contains("preset.d");
        assertThat(JsonPath.read(result, "$.failed[0].pluginId").toString())
                .isEqualTo("preset.ghost");
        assertThat(JsonPath.read(result, "$.failed[0].action").toString()).isEqualTo("activate");
        assertThat(JsonPath.read(result, "$.failed[0].message").toString())
                .contains("插件版本不存在");
    }

    @Test
    void presetNameValidationAndDuplicate() throws Exception {
        saveExpectBadRequest("  ", "预设名称需为 1-50 个字符");
        saveExpectBadRequest("a".repeat(51), "预设名称需为 1-50 个字符");
        savePreset("唯一场景");
        saveExpectBadRequest("唯一场景", "同名预设已存在");
    }

    @Test
    void presetEndpointsRequireAdmin() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/presets")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/presets")
                        .header("Authorization", userBearer)
                        .contentType("application/json").content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/presets/p1/apply")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/presets/p1")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void presetDeleteRemovesAndApplyThen404() throws Exception {
        String presetId = savePreset("待删场景");
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/presets/" + presetId)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/presets/" + presetId + "/apply")
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    private String savePreset(String name) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/presets")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private void saveExpectBadRequest(String name, String fragment) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/presets")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(fragment)));
    }

    private String applyPreset(String presetId) throws Exception {
        return mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/presets/" + presetId + "/apply")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 经 inventory 定位插件当前占用激活并停用（走正式端点）。 */
    private void stopByPluginId(String pluginId) throws Exception {
        String inventory = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/plugins/inventory")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        for (String id : ids) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + id + "/stop")
                            .header("Authorization", adminBearer))
                    .andExpect(status().isOk());
        }
    }
}
