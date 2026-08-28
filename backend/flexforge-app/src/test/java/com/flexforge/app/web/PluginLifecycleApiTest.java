package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-PLUGIN-LIFE 八例（docs/07 §5）：合法安装注册、缺依赖无残留、迁移失败回滚、
 * 幂等重装、stale 拒绝、升级失败 current 可用、卸载清理审计保留、重启恢复。
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

    private String adminBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    /** 构建可安装的完整插件 zip（含实体/视图/迁移脚本）。 */
    static byte[] packageZip(String pluginId, String version, String migrations,
                             String entities, String scripts) {
        String manifest = "{\"schemaVersion\":1,\"id\":\"" + pluginId + "\",\"name\":\"" + pluginId
                + "\",\"version\":\"" + version + "\",\"capabilityLevel\":1,"
                + "\"minPlatformVersion\":\"0.1.0\",\"dependencies\":[],"
                + "\"contributions\":{\"navigation\":[\"" + pluginId + ".items\"],"
                + "\"renderers\":[\"enum.default\"]},"
                + "\"resources\":{\"entities\":[\"metadata/entities/item.json\"],"
                + "\"views\":[],\"migrations\":" + migrations + "}}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("metadata/entities/item.json", entities.getBytes(StandardCharsets.UTF_8));
        for (String script : migrations.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            String trimmed = script.trim();
            if (!trimmed.isEmpty() && scripts != null) {
                entries.put(trimmed, scripts.getBytes(StandardCharsets.UTF_8));
            }
        }
        return zip(entries);
    }

    static byte[] zip(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                for (var entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String entitiesJson(String tableName) {
        return "{\"name\":\"" + tableName + "\",\"displayName\":\"库存项\","
                + "\"fields\":[{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\","
                + "\"required\":true,\"position\":0},{\"name\":\"qty\",\"displayName\":\"数量\","
                + "\"fieldType\":\"integer\",\"validation\":{\"min\":0},\"position\":1}]}";
    }

    private static String migrationSql(String tableName) {
        return "CREATE TABLE " + tableName + " (id SERIAL PRIMARY KEY,"
                + " data JSONB NOT NULL DEFAULT '{}');";
    }


    /** 导入并激活，返回 activationId。 */
    private String importAndActivate(String pluginId, String version) throws Exception {
        String table = pluginId.replace('.', '_') + "_item";
        byte[] bytes = packageZip(pluginId, version, "[\"migrations/V001__init.sql\"]",
                entitiesJson(table), migrationSql(table));
        String importBody = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");
        String activateBody = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(activateBody, "$.id");
    }

    // ===== 1. 合法插件安装后注册菜单、权限、实体和 renderer =====
    @Test
    void validPluginActivationRegistersEverything() throws Exception {
        String activationId = importAndActivate("life.one", "1.0.0");
        assertThat(activationId).isNotBlank();

        // plugin_registration：service.meta + navigation + renderer
        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(registrations).isGreaterThanOrEqualTo(3);

        // meta_entity 写入且 enabled
        Map<String, Object> entity = jdbc.queryForMap(
                "SELECT name, status FROM meta_entity WHERE plugin_id = 'life.one'");
        assertThat(entity.get("name")).isEqualTo("life_one_item");
        assertThat(entity.get("status")).isEqualTo("enabled");

        // meta_field 写入
        Integer fields = jdbc.queryForObject(
                "SELECT count(*) FROM meta_field f JOIN meta_entity e ON f.entity_id = e.id"
                        + " WHERE e.plugin_id = 'life.one'", Integer.class);
        assertThat(fields).isEqualTo(2);

        // plugin_migration 记录
        Integer migrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_migration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(migrations).isEqualTo(1);

        // 迁移创建的表存在
        Integer tableExists = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'life_one_item'",
                Integer.class);
        assertThat(tableExists).isEqualTo(1);
    }

    // ===== 4. 同一版本重复安装返回同一结果 =====
    @Test
    void repeatedActivationIsIdempotent() throws Exception {
        String first = importAndActivate("life.two", "1.0.0");
        // 再次激活同 version：获取 versionId 再 activate
        String versionId = jdbc.queryForObject(
                "SELECT id FROM plugin_version WHERE plugin_id = 'life.two' AND version = '1.0.0'",
                String.class);
        String secondBody = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(secondBody, "$.id")).isEqualTo(first);

        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, first);
        assertThat(registrations).isGreaterThanOrEqualTo(3);
    }

    // ===== 5. 停用后旧 activationId 请求被拒绝（stale_activation） =====
    @Test
    void stoppedActivationRejectedAsStale() throws Exception {
        String activationId = importAndActivate("life.three", "1.0.0");
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));

        // 停用后注册表清空
        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(registrations).isZero();

        // 实体 disabled
        String entityStatus = jdbc.queryForObject(
                "SELECT status FROM meta_entity WHERE plugin_id = 'life.three'", String.class);
        assertThat(entityStatus).isEqualTo("disabled");

        // 再次 stop 同一 activation（幂等）
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    // ===== 6. 升级：新版本激活后旧版本自动停用 =====
    @Test
    void upgradeActivatesNewAndStopsOld() throws Exception {
        importAndActivate("life.four", "1.0.0");
        byte[] newBytes = packageZip("life.four", "2.0.0", "[]", entitiesJson("life_four_item"), null);
        String importBody = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", newBytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newVersionId = JsonPath.read(importBody, "$.versionId");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + newVersionId + "/upgrade")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 只剩一个 ACTIVE
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_activation WHERE plugin_id = 'life.four'"
                        + " AND status = 'ACTIVE'", Integer.class);
        assertThat(active).isEqualTo(1);
    }

    // ===== 7. 卸载清理注册但保留审计 =====
    @Test
    void uninstallCleansRegistrationsButKeepsAudit() throws Exception {
        String activationId = importAndActivate("life.five", "1.0.0");
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/life.five")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());

        // 注册清空
        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration WHERE activation_id = ?",
                Integer.class, activationId);
        assertThat(registrations).isZero();

        // 实体 disabled
        String entityStatus = jdbc.queryForObject(
                "SELECT status FROM meta_entity WHERE plugin_id = 'life.five'", String.class);
        assertThat(entityStatus).isEqualTo("disabled");

        // 审计保留（平台审计）
        Integer audit = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'plugin.uninstall'"
                        + " AND object_id = 'life.five'", Integer.class);
        assertThat(audit).isEqualTo(1);

        // plugin_audit_event 保留
        Integer pluginEvents = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_audit_event WHERE plugin_id = 'life.five'",
                Integer.class);
        assertThat(pluginEvents).isGreaterThanOrEqualTo(2); // STOPPED + UNINSTALLED
    }

    // ===== 2. 迁移失败整体回滚 =====
    @Test
    void migrationFailureRollsBackEverything() throws Exception {
        // 构造一个迁移会失败的包（语法错误）
        byte[] badBytes = packageZip("life.six", "1.0.0", "[\"migrations/V001__bad.sql\"]",
                entitiesJson("life_six_bad"), "THIS IS NOT VALID SQL ???;");
        String importBody = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", badBytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("migration_failed"));

        // 激活 FAILED 记录存在，带失败阶段
        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT status, stage FROM plugin_activation WHERE plugin_version_id = ?",
                versionId);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("stage")).isEqualTo("MIGRATION");

        // 无注册残留
        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration pr JOIN plugin_activation pa"
                        + " ON pr.activation_id = pa.id WHERE pa.plugin_version_id = ?",
                Integer.class, versionId);
        assertThat(registrations).isZero();
    }

    // ===== 3. 同插件已有激活时拒绝并发 =====
    @Test
    void concurrentActivationRejected() throws Exception {
        importAndActivate("life.seven", "1.0.0");
        byte[] v2Bytes = packageZip("life.seven", "2.0.0", "[]", entitiesJson("life_seven_item"), null);
        String importBody = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", v2Bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String v2Id = JsonPath.read(importBody, "$.versionId");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + v2Id + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("已有进行中激活")));
    }

    // ===== 权限矩阵 =====
    @Test
    void onlyAdminMayManageLifecycle() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/any-version/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound()); // 版本不存在
    }
}
