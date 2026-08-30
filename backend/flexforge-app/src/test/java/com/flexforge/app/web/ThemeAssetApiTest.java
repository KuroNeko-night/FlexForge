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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主题资产聚合与 tokens 通道（docs/09 P12.5、登记册 §2.2 kind 扩展）：
 * theme-assets 端点对普通登录用户可见（前端壳层消费面）；tokens JSON 经
 * serve 端点取回；停用后聚合为空（换肤可撤销）；kind 非法拒绝导入。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ThemeAssetApiTest {

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

    private static byte[] themeZip(String themeAssetsJson) {
        String manifest = "{\"schemaVersion\":1,\"id\":\"theme.test\",\"name\":\"测试主题\","
                + "\"version\":\"1.0.0\",\"capabilityLevel\":1,\"minPlatformVersion\":\"0.1.0\","
                + "\"dependencies\":[],\"contributions\":{\"navigation\":[],\"renderers\":[],"
                + "\"themeAssets\":" + themeAssetsJson + "},"
                + "\"resources\":{\"entities\":[],\"views\":[],\"migrations\":[]}}";
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        return PluginPackageTestSupport.zip(Map.of(
                "plugin.json", manifest.getBytes(utf8),
                "assets/theme.json", "{\"--ff-primary\":\"#c2571c\"}".getBytes(utf8),
                "assets/bg.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(utf8)));
    }

    @Test
    void themeAssetsAggregateForLoggedInUsersAndRevokeOnStop() throws Exception {
        String activationId = importAndActivateTheme();
        assertAggregateVisibleToUser(activationId);
        assertTokensServed(activationId);

        // 停用后聚合为空（换肤可撤销）
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/theme-assets")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private String importAndActivateTheme() throws Exception {
        String themeAssets = "[{\"key\":\"theme.test.bg\",\"kind\":\"background\","
                + "\"path\":\"assets/bg.svg\"},"
                + "{\"key\":\"theme.test.tokens\",\"kind\":\"tokens\","
                + "\"path\":\"assets/theme.json\"}]";
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "theme.zip", "application/zip",
                                        themeZip(themeAssets)))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(PluginPackageTestSupport.activate(mockMvc, adminBearer,
                (String) JsonPath.read(importBody, "$.versionId")), "$.id");
    }

    private void assertAggregateVisibleToUser(String activationId) throws Exception {
        // 普通登录用户可见（壳层消费面；非管理员）
        String aggregate = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/theme-assets")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> kinds = JsonPath.read(aggregate, "$[*].kind");
        assertThat(kinds).containsExactlyInAnyOrder("background", "tokens");
        String tokensPath = JsonPath.read(aggregate, "$[?(@.kind=='tokens')].path").toString();
        assertThat(tokensPath).contains("theme.json");
        assertThat((String) JsonPath.read(aggregate, "$[0].activationId")).isEqualTo(activationId);
    }

    private void assertTokensServed(String activationId) throws Exception {
        // tokens JSON 经 serve 端点取回（同前端消费路径）
        mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/activations/" + activationId + "/assets/assets/theme.json")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['--ff-primary']").value("#c2571c"));
    }

    @Test
    void illegalKindIsRejectedAtImport() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "theme.zip", "application/zip",
                                themeZip("[{\"key\":\"k\",\"kind\":\"palette\","
                                        + "\"path\":\"assets/bg.svg\"}]")))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_manifest"));
    }
}
