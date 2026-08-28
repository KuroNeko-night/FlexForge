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

import java.util.Map;

import static com.flexforge.app.web.PluginPackageTestSupport.activate;
import static com.flexforge.app.web.PluginPackageTestSupport.defaultBody;
import static com.flexforge.app.web.PluginPackageTestSupport.importAndActivate;
import static com.flexforge.app.web.PluginPackageTestSupport.importVersion;
import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-PLUGIN-LIFE 激活侧用例（docs/07 §5-1/2/3/4）：合法安装注册（含菜单可见）、
 * 缺依赖 DEPENDENCY_CHECK 失败无残留、同插件占用拒绝、幂等重装、实体归属冲突、
 * 非管理员拒绝。停用/升级/重启恢复见 PluginLifecycleApiTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PluginActivationApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    // ===== §5-1 合法插件安装后注册菜单、权限、实体和 renderer =====
    @Test
    void validPluginActivationRegistersEverything() throws Exception {
        String activationId = importAndActivate(mockMvc, adminBearer, "life.one", "1.0.0");
        assertThat(activationId).isNotBlank();

        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(registrations).isGreaterThanOrEqualTo(3);

        Map<String, Object> entity = jdbc.queryForMap(
                "SELECT name, status FROM meta_entity WHERE plugin_id = 'life.one'");
        assertThat(entity.get("name")).isEqualTo("life_one_item");
        assertThat(entity.get("status")).isEqualTo("enabled");

        Integer fields = jdbc.queryForObject(
                "SELECT count(*) FROM meta_field f JOIN meta_entity e ON f.entity_id = e.id"
                        + " WHERE e.plugin_id = 'life.one'", Integer.class);
        assertThat(fields).isEqualTo(2);

        Integer migrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_migration pm JOIN plugin_activation pa"
                        + " ON pm.activation_id = pa.id WHERE pa.id = ?",
                Integer.class, activationId);
        assertThat(migrations).isEqualTo(1);

        Integer tableExists = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'life_one_item'",
                Integer.class);
        assertThat(tableExists).isEqualTo(1);

        // §2.2 契约载荷：菜单接口可见插件导航（route 指向动态实体列表页）
        assertThat(menuKeys(mockMvc, adminBearer)).contains("life.one.items");
    }

    // ===== §5-2 缺失依赖在 DEPENDENCY_CHECK 失败，无残留；补齐后可恢复 =====
    @Test
    void missingDependencyFailsAtDependencyCheckWithoutResidue() throws Exception {
        String baseVersionId = importVersion(mockMvc, adminBearer, "dep.base", "1.0.0",
                defaultBody("dep_base_item", "dep_base_item"));
        String dependentVersionId = importVersion(mockMvc, adminBearer, "life.dep", "1.0.0",
                new PluginPackageTestSupport.PackageBody("[\"migrations/V001__init.sql\"]",
                        PluginPackageTestSupport.entitiesJson("life_dep_item"),
                        PluginPackageTestSupport.migrationSql("life_dep_item"),
                        "[{\"pluginId\":\"dep.base\",\"versionRange\":\"^1.0.0\"}]"));

        // 依赖仅导入未激活 → 激活拒绝
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + dependentVersionId
                        + "/activate").header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("dependency_missing"));

        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT status, stage FROM plugin_activation WHERE plugin_version_id = ?",
                dependentVersionId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("stage")).isEqualTo("DEPENDENCY_CHECK");

        assertThat(residueCountOf(dependentVersionId)).isZero();
        assertThat(entityCountOf("life.dep")).isZero();

        // 激活依赖后重试成功
        activate(mockMvc, adminBearer, baseVersionId);
        activate(mockMvc, adminBearer, dependentVersionId);
        assertThat(menuKeys(mockMvc, adminBearer)).contains("life.dep.items");
    }

    // ===== §5-3 同插件已有激活时拒绝并发 =====
    @Test
    void concurrentActivationRejected() throws Exception {
        importAndActivate(mockMvc, adminBearer, "life.seven", "1.0.0");
        String v2Id = importVersion(mockMvc, adminBearer, "life.seven", "2.0.0",
                new PluginPackageTestSupport.PackageBody("[]",
                        PluginPackageTestSupport.entitiesJson("life_seven_item"), null, "[]"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + v2Id + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已有进行中激活")));
    }

    // ===== §5-4 同一版本重复安装返回同一结果 =====
    @Test
    void repeatedActivationIsIdempotent() throws Exception {
        String first = importAndActivate(mockMvc, adminBearer, "life.two", "1.0.0");
        String versionId = jdbc.queryForObject(
                "SELECT id FROM plugin_version WHERE plugin_id = 'life.two' AND version = '1.0.0'",
                String.class);
        String secondBody = activate(mockMvc, adminBearer, versionId);
        assertThat((String) JsonPath.read(secondBody, "$.id")).isEqualTo(first);

        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, first);
        assertThat(registrations).isGreaterThanOrEqualTo(3);
        assertThat(menuKeys(mockMvc, adminBearer)).contains("life.two.items");
    }

    // ===== 实体归属冲突：同名实体归属其他插件时拒绝，不静默覆盖 =====
    @Test
    void entityOwnershipConflictIsRejected() throws Exception {
        importAndActivate(mockMvc, adminBearer, "owner.one", "1.0.0");
        String intruderVersionId = importVersion(mockMvc, adminBearer, "owner.two", "1.0.0",
                defaultBody("owner_one_item", "owner_two_tab"));

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + intruderVersionId
                        + "/activate").header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("registration_failed"));

        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT status, stage FROM plugin_activation WHERE plugin_version_id = ?",
                intruderVersionId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("stage")).isEqualTo("REGISTER");

        // 原归属插件实体未被覆盖，字段完整
        Map<String, Object> entity = jdbc.queryForMap(
                "SELECT status FROM meta_entity WHERE name = 'owner_one_item'");
        assertThat(entity.get("status")).isEqualTo("enabled");
        Integer fields = jdbc.queryForObject(
                "SELECT count(*) FROM meta_field f JOIN meta_entity e ON f.entity_id = e.id"
                        + " WHERE e.plugin_id = 'owner.one'", Integer.class);
        assertThat(fields).isEqualTo(2);
        assertThat(residueCountOf(intruderVersionId)).isZero();
    }

    // ===== 权限矩阵：非管理员不可管理生命周期 =====
    @Test
    void nonAdminCannotManageLifecycle() throws Exception {
        String versionId = importVersion(mockMvc, adminBearer, "life.perm", "1.0.0",
                defaultBody("life_perm_item", "life_perm_item"));
        String developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        String userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", developerBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/act-any/stop")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/activations/act-any/registrations")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    private Integer residueCountOf(String versionId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration pr JOIN plugin_activation pa"
                        + " ON pr.activation_id = pa.id WHERE pa.plugin_version_id = ?",
                Integer.class, versionId);
    }

    private Integer entityCountOf(String pluginId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM meta_entity WHERE plugin_id = ?", Integer.class, pluginId);
    }
}
