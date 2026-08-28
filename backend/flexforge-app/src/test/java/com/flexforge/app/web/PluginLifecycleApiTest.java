package com.flexforge.app.web;

import com.flexforge.plugin.application.PluginLifecycleService;
import com.flexforge.runtime.InMemoryExtensionRegistry;
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

import java.util.Map;

import static com.flexforge.app.web.PluginPackageTestSupport.PackageBody;
import static com.flexforge.app.web.PluginPackageTestSupport.defaultBody;
import static com.flexforge.app.web.PluginPackageTestSupport.entitiesJson;
import static com.flexforge.app.web.PluginPackageTestSupport.importAndActivate;
import static com.flexforge.app.web.PluginPackageTestSupport.importVersion;
import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-PLUGIN-LIFE 生命周期侧用例（docs/07 §5-5/6/7/8）：停用清理 + stale 拒绝、
 * 升级成功停旧 / 失败补偿回旧版（current 保持可用）、卸载审计保留、重启恢复。
 * 激活侧用例见 PluginActivationApiTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PluginLifecycleApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PluginLifecycleService lifecycleService;

    @Autowired
    private InMemoryExtensionRegistry extensionRegistry;

    private String adminBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    // ===== §5-5 停用后旧 activationId 请求被拒绝（stale_activation）=====
    @Test
    void stoppedActivationRejectedAsStale() throws Exception {
        String activationId = importAndActivate(mockMvc, adminBearer, "life.three", "1.0.0");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(registrations).isZero();

        String entityStatus = jdbc.queryForObject(
                "SELECT status FROM meta_entity WHERE plugin_id = 'life.three'", String.class);
        assertThat(entityStatus).isEqualTo("disabled");

        // 旧 activationId 的注册清单请求 → 409 stale_activation；菜单入口撤销
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/activations/" + activationId + "/registrations")
                        .header("Authorization", adminBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale_activation"));
        assertThat(menuKeys(mockMvc, adminBearer)).doesNotContain("life.three.items");

        // 再次 stop 同一 activation（幂等）
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    // ===== §5-6a 升级成功：新版本 ACTIVE，旧 activationId 过期 =====
    @Test
    void upgradeActivatesNewAndStalesOldId() throws Exception {
        String oldActivationId = importAndActivate(mockMvc, adminBearer, "life.four", "1.0.0");
        String newVersionId = importVersion(mockMvc, adminBearer, "life.four", "2.0.0",
                new PackageBody("[]", entitiesJson("life_four_item"), null, "[]"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + newVersionId + "/upgrade")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_activation WHERE plugin_id = 'life.four'"
                        + " AND status = 'ACTIVE'", Integer.class);
        assertThat(active).isEqualTo(1);

        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/activations/" + oldActivationId + "/registrations")
                        .header("Authorization", adminBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale_activation"));
    }

    // ===== §5-6b 升级失败：补偿重激活旧版本，current 保持可用 =====
    @Test
    void upgradeFailureRestoresCurrentVersion() throws Exception {
        String currentVersionId = importVersion(mockMvc, adminBearer, "life.six", "1.0.0",
                defaultBody("life_six_item", "life_six_item"));
        String currentActivationId = com.jayway.jsonpath.JsonPath.read(
                PluginPackageTestSupport.activate(mockMvc, adminBearer, currentVersionId), "$.id");
        String badVersionId = importVersion(mockMvc, adminBearer, "life.six", "2.0.0",
                new PackageBody("[\"migrations/V001__bad.sql\"]", entitiesJson("life_six_item"),
                        "THIS IS NOT VALID SQL ???;", "[]"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + badVersionId + "/upgrade")
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("migration_failed"));

        // 新版本 FAILED@MIGRATION；旧版本经补偿回到 ACTIVE
        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT status, stage FROM plugin_activation WHERE plugin_version_id = ?",
                badVersionId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("stage")).isEqualTo("MIGRATION");
        String activeVersionId = jdbc.queryForObject(
                "SELECT plugin_version_id FROM plugin_activation WHERE plugin_id = 'life.six'"
                        + " AND status = 'ACTIVE'", String.class);
        assertThat(activeVersionId).isEqualTo(currentVersionId);

        // 补偿后实体可用、菜单入口仍在；补偿产生的新 current 激活可查询
        String entityStatus = jdbc.queryForObject(
                "SELECT status FROM meta_entity WHERE plugin_id = 'life.six'", String.class);
        assertThat(entityStatus).isEqualTo("enabled");
        assertThat(menuKeys(mockMvc, adminBearer)).contains("life.six.items");
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/activations/" + currentActivationId + "/registrations")
                        .header("Authorization", adminBearer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stale_activation"));
    }

    // ===== §5-7 卸载清理注册但保留审计 =====
    @Test
    void uninstallCleansRegistrationsButKeepsAudit() throws Exception {
        String activationId = importAndActivate(mockMvc, adminBearer, "life.five", "1.0.0");
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/life.five")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());

        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(registrations).isZero();

        String entityStatus = jdbc.queryForObject(
                "SELECT status FROM meta_entity WHERE plugin_id = 'life.five'", String.class);
        assertThat(entityStatus).isEqualTo("disabled");
        assertThat(menuKeys(mockMvc, adminBearer)).doesNotContain("life.five.items");

        Integer audit = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'plugin.uninstall'"
                        + " AND object_id = 'life.five'", Integer.class);
        assertThat(audit).isEqualTo(1);

        Integer pluginEvents = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_audit_event WHERE plugin_id = 'life.five'",
                Integer.class);
        assertThat(pluginEvents).isGreaterThanOrEqualTo(2);
    }

    // ===== §5-8 重启恢复：内存注册丢失后仅恢复持久化 ACTIVE 插件 =====
    @Test
    void restartRebuildsInMemoryRegistrations() throws Exception {
        String activationId = importAndActivate(mockMvc, adminBearer, "life.eight", "1.0.0");
        assertThat(menuKeys(mockMvc, adminBearer)).contains("life.eight.items");

        // 模拟重启丢失内存注册（DB 状态不动）
        extensionRegistry.closeAll(activationId);
        assertThat(menuKeys(mockMvc, adminBearer)).doesNotContain("life.eight.items");

        int restored = lifecycleService.restoreActivePlugins();
        assertThat(restored).isGreaterThanOrEqualTo(1);
        assertThat(menuKeys(mockMvc, adminBearer)).contains("life.eight.items");
    }
}
