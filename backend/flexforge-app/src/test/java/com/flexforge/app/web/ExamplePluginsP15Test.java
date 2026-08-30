package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P15 示例插件验收（docs/09 P15）：example.library / example.facility 与库存示例
 * 同装共存（元数据驱动泛化：字段类型/视图组合差异，六类字段中覆盖 decimal/
 * boolean/date），locale.en 语言包经 kind=locale 通道聚合下发（FR-SETUP-02 消费面）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExamplePluginsP15Test {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 类级单例包（zip 时间戳字节漂移会使 contentHash 变化，须只构建一次）。 */
    private static byte[] libraryZip;
    private static byte[] facilityZip;
    private static byte[] localeZip;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeAll
    static void buildPackages() throws Exception {
        libraryZip = zipPackage(Path.of("..", "..", "plugins", "example-library"));
        facilityZip = zipPackage(Path.of("..", "..", "plugins", "example-facility"));
        localeZip = zipPackage(Path.of("..", "..", "plugins", "locale-en"));
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    @Test
    void libraryAndFacilityCoexistWithInventorySemantics() throws Exception {
        String libraryActivation = importAndActivate(libraryZip);
        String facilityActivation = importAndActivate(facilityZip);
        assertThat(libraryActivation).isNotBlank();
        assertThat(facilityActivation).isNotBlank();

        // 与库存示例同装：两个新导航并存（FR-PLUGIN-09 同口径）
        assertThat(menuKeys(mockMvc, userBearer)).contains("example.library.books");
        assertThat(menuKeys(mockMvc, userBearer)).contains("example.facility.checks");

        // 迁移落地（library 建表+种子；facility 建表）
        Integer seeds = jdbc.queryForObject(
                "SELECT count(*) FROM example_library_book", Integer.class);
        assertThat(seeds).isEqualTo(3);
        Integer facilityTable = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_name = 'example_facility_check'", Integer.class);
        assertThat(facilityTable).isEqualTo(1);

        libraryCrudCoversDecimalBooleanAndDate();
        libraryRejectsNegativePrice();
        facilityCrudCoversBooleanAndDate();
    }

    // 普通用户 CRUD：decimal/boolean/date 类型往返（六类字段的泛化证据）
    private void libraryCrudCoversDecimalBooleanAndDate() throws Exception {
        String created = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/data/library_book")
                                .header("Authorization", userBearer)
                                .contentType("application/json")
                                .content("{\"title\":\"领域驱动设计\",\"author\":\"Evans\","
                                        + "\"category\":\"技术\",\"price\":98.5,"
                                        + "\"available\":true,\"acquired_on\":\"2026-08-30\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/library_book/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(98.5))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.acquired_on").value("2026-08-30"));
    }

    private void libraryRejectsNegativePrice() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/library_book")
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"title\":\"坏数据\",\"author\":\"x\",\"category\":\"技术\","
                                + "\"price\":-1,\"available\":true}"))
                .andExpect(status().isBadRequest());
    }

    private void facilityCrudCoversBooleanAndDate() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/facility_check")
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"device\":\"空压机-1\",\"level\":\"关键\","
                                + "\"downtime_minutes\":30,\"passed\":false,"
                                + "\"checked_on\":\"2026-08-30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passed").value(false));
    }

    @Test
    void localePackAggregatesWithSignedUrlAndDisappearsOnStop() throws Exception {
        String activationId = importAndActivate(localeZip);
        String aggregation = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/plugins/theme-assets")
                                .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> kinds = JsonPath.read(aggregation, "$[?(@.pluginId == 'locale.en')].kind");
        assertThat(kinds).containsExactly("locale");
        List<String> serveUrls = JsonPath.read(aggregation,
                "$[?(@.pluginId == 'locale.en')].serveUrl");
        assertThat(serveUrls).hasSize(1);
        String serveUrl = serveUrls.get(0);
        assertThat(serveUrl).contains("sig=");

        // 签名 URL 匿名可取语言包 JSON（lang/messages 契约，FR-SETUP-02 消费面）
        String pack = mockMvc.perform(MockMvcRequestBuilders.get(serveUrl))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(pack, "$.lang")).isEqualTo("en");
        assertThat((String) JsonPath.read(pack, "$.messages['plugins.title']"))
                .isEqualTo("Plugins");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        String afterStop = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/plugins/theme-assets")
                                .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> remaining = JsonPath.read(afterStop,
                "$[?(@.pluginId == 'locale.en')].pluginId");
        assertThat(remaining).isEmpty();
    }

    private String importAndActivate(byte[] zip) throws Exception {
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");
        return JsonPath.read(
                PluginPackageTestSupport.activate(mockMvc, adminBearer, versionId), "$.id");
    }

    /** 仓库内插件目录打包为 zip（与 ExampleInventoryPluginTest 同构）。 */
    private static byte[] zipPackage(Path packageDir) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                zos.putNextEntry(new java.util.zip.ZipEntry(
                        packageDir.relativize(file).toString().replace('\\', '/')));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
