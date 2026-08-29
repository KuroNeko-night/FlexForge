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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.flexforge.app.web.PluginPackageTestSupport.entitiesJson;
import static com.flexforge.app.web.PluginPackageTestSupport.migrationSql;
import static com.flexforge.app.web.PluginPackageTestSupport.zip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 插件静态资产链路（theme-asset，Issue #20 第 3/4 项收口）：themeAssets 声明
 * 导入→激活注册→serve 端点（CSP/attachment/nosniff 纵深）；assets/ 逐文件声明
 * 收紧负例；stale/未认证/未知路径失败路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PluginAssetApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\""
            + " viewBox=\"0 0 4 4\"><rect width=\"4\" height=\"4\" fill=\"#2c5f8a\"/></svg>";

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

    // ===== themeAssets 包：导入→激活注册→serve（含安全头与字节一致性）=====
    @Test
    void themeAssetPackageActivatesAndServesWithDefenseHeaders() throws Exception {
        String activationId = importAndActivateAssetPlugin("asset.one",
                "\"themeAssets\":[{\"key\":\"asset.one.bg\",\"kind\":\"background\","
                        + "\"path\":\"assets/bg.svg\"}]");

        // plugin_registration 记录 theme-asset 贡献（载荷含 §2.2 契约字段）
        Map<String, Object> registration = jdbc.queryForMap(
                "SELECT registration_key, payload_json::text AS payload_json"
                        + " FROM plugin_registration"
                        + " WHERE activation_id = ? AND extension_type = 'extension.theme-asset'",
                activationId);
        assertThat(registration.get("registration_key")).isEqualTo("asset.one.bg");
        assertThat((String) registration.get("payload_json"))
                .contains("assets/bg.svg").contains("background").contains("asset.one.bg");

        // serve：登录即可读（USER），三重纵深响应头，字节与包内一致
        String userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
        byte[] body = mockMvc.perform(MockMvcRequestBuilders.get(assetUrl(activationId, "assets/bg.svg"))
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'"))
                .andExpect(header().string("Content-Disposition", "attachment"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(SVG);

        // asset_payloads 存储（base64 可回读）
        String stored = jdbc.queryForObject(
                "SELECT asset_payloads ->> 'assets/bg.svg' FROM plugin_version pv"
                        + " JOIN plugin_activation pa ON pa.plugin_version_id = pv.id"
                        + " WHERE pa.id = ?", String.class, activationId);
        assertThat(Base64.getDecoder().decode(stored)).isEqualTo(SVG.getBytes(StandardCharsets.UTF_8));
    }

    // ===== Issue #20 第 3 项：assets/ 逐文件声明收紧 =====
    @Test
    void undeclaredAssetFileRejected() throws Exception {
        byte[] bytes = assetZip("asset.two", "\"themeAssets\":[]",
                Map.of("assets/undeclared.png", pngBytes()));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value(
                                org.hamcrest.Matchers.containsString("未被 contributions.themeAssets 声明")));
    }

    // ===== themeAssets 声明的资产包内缺失 =====
    @Test
    void declaredAssetMissingRejected() throws Exception {
        byte[] bytes = assetZip("asset.three",
                "\"themeAssets\":[{\"key\":\"asset.three.bg\",\"kind\":\"background\","
                        + "\"path\":\"assets/ghost.svg\"}]",
                Map.of());
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.message").value(
                                org.hamcrest.Matchers.containsString("包内缺失")));
    }

    // ===== kind 非法拒绝 =====
    @Test
    void invalidThemeAssetKindRejected() throws Exception {
        byte[] bytes = assetZip("asset.four",
                "\"themeAssets\":[{\"key\":\"asset.four.bg\",\"kind\":\"wallpaper\","
                        + "\"path\":\"assets/bg.svg\"}]",
                Map.of("assets/bg.svg", SVG.getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest());
    }

    // ===== serve 失败路径：未知路径 404 / 路径穿越样式串 404 =====
    @Test
    void unknownAssetPathReturnsNotFound() throws Exception {
        String activationId = importAndActivateAssetPlugin("asset.five",
                "\"themeAssets\":[{\"key\":\"asset.five.bg\",\"kind\":\"background\","
                        + "\"path\":\"assets/bg.svg\"}]");
        mockMvc.perform(MockMvcRequestBuilders.get(
                        assetUrl(activationId, "assets/nope.svg"))
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.get(
                        assetUrl(activationId, "assets/../plugin.json"))
                        .header("Authorization", adminBearer))
                .andExpect(status().isNotFound());
    }

    // ===== serve 失败路径：stale 激活 409 / 未认证 401；停用撤销内存注册 =====
    @Test
    void staleAndUnauthenticatedAssetRequestsRejected() throws Exception {
        String activationId = importAndActivateAssetPlugin("asset.six",
                "\"themeAssets\":[{\"key\":\"asset.six.bg\",\"kind\":\"background\","
                        + "\"path\":\"assets/bg.svg\"}]");
        mockMvc.perform(MockMvcRequestBuilders.get(assetUrl(activationId, "assets/bg.svg")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get(assetUrl(activationId, "assets/bg.svg"))
                        .header("Authorization", adminBearer))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("stale_activation"));
    }

    private String importAndActivateAssetPlugin(String pluginId, String themeAssetsJson)
            throws Exception {
        byte[] bytes = assetZip(pluginId, themeAssetsJson,
                Map.of("assets/bg.svg", SVG.getBytes(StandardCharsets.UTF_8)));
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                        bytes))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");
        String activateBody = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(activateBody, "$.id");
    }

    /** themeAssets 注入 contributions 的包（默认含实体+迁移，资产按参数附加）。 */
    private static byte[] assetZip(String pluginId, String themeAssetsJson,
                                   Map<String, byte[]> assetFiles) {
        String manifest = "{\"schemaVersion\":1,\"id\":\"" + pluginId + "\",\"name\":\"" + pluginId
                + "\",\"version\":\"1.0.0\",\"capabilityLevel\":1,"
                + "\"minPlatformVersion\":\"0.1.0\",\"dependencies\":[],"
                + "\"contributions\":{\"navigation\":[\"" + pluginId + ".items\"],"
                + "\"renderers\":[\"enum.default\"]," + themeAssetsJson + "},"
                + "\"resources\":{\"entities\":[\"metadata/entities/item.json\"],\"views\":[],"
                + "\"migrations\":[\"migrations/V001__init.sql\"]}}";
        String table = pluginId.replace('.', '_') + "_item";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("metadata/entities/item.json",
                entitiesJson(table).getBytes(StandardCharsets.UTF_8));
        entries.put("migrations/V001__init.sql",
                migrationSql(table).getBytes(StandardCharsets.UTF_8));
        entries.putAll(assetFiles);
        return zip(entries);
    }

    private static String assetUrl(String activationId, String path) {
        return "/api/v1/plugins/activations/" + activationId + "/assets/" + path;
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }
}
