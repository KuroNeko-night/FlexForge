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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P23 文件处理器验收（docs/09 P23，FR-PLUGIN-14）：example-filetools 两处理器
 * 文件进文件出闭环（清洗产物可下载且内容正确）、上传三重校验失败路径
 * （扩展名/魔数/大小）、entity 处理器拒文件输入、产物归属与过期
 * （404 防枚举 / 410 artifact_expired）。宿主需有 python3/python。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExampleFileToolsApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Path FILETOOLS_DIR = Path.of("..", "..", "plugins", "example-filetools");
    private static final Path ANALYTICS_DIR = Path.of("..", "..", "plugins", "example-analytics");

    /** 含 BOM、空行、重复行与带空白列名的清洗样例。 */
    private static final byte[] SAMPLE_CSV = (
            "\uFEFFname, qty ,remark\r\n\r\nA001,3,ok\r\nA001,3,ok\r\nB002,,bad\r\n")
            .getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;
    private String developerBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        ensureActivated(FILETOOLS_DIR, "example.filetools");
    }

    @Test
    void cleanChainProducesDownloadableArtifact() throws Exception {
        String result = invokeFile("filetools.csv.clean", "data.csv", SAMPLE_CSV, userBearer);
        assertThat(result).contains("\"kind\":\"file\"");
        assertThat(result).contains("\"filename\":\"cleaned.csv\"");
        String artifactId = JsonPath.read(result, "$.artifactId");

        String downloaded = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/plugins/processors/artifacts/"
                                        + artifactId + "/download")
                                .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition", containsString("cleaned.csv")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 去空行/去重（A001 仅一次）/列名规范化（去空白）；行尾统一 \n 后整体比对
        assertThat(downloaded.replaceAll("\r\n", "\n").trim())
                .isEqualTo("name,qty,remark\nA001,3,ok\nB002,,bad");
        Integer downloadedAt = jdbc.queryForObject(
                "SELECT count(*) FROM processor_artifact WHERE id = ?"
                        + " AND downloaded_at IS NOT NULL", Integer.class, artifactId);
        assertThat(downloadedAt).isEqualTo(1);
    }

    @Test
    void profileOutputsTableContract() throws Exception {
        String result = invokeFile("filetools.csv.profile", "data.csv", SAMPLE_CSV, userBearer);
        assertThat(result).contains("\"kind\":\"table\"");
        assertThat(result).contains("数据行数").contains("3");
        assertThat(result).contains("qty").contains("66.67%");
    }

    @Test
    void uploadTripleValidationRejectsWithStableCode() throws Exception {
        // 扩展名不在声明白名单
        invokeFileExpect("filetools.csv.clean", "data.json", "{}".getBytes(), userBearer,
                new Expectation("processor_input_invalid", "白名单"));
        // 伪装文本（csv 扩展名 + 二进制内容：嗅探前 4KB 含 NUL 拒绝）
        byte[] binary = new byte[64];
        binary[0] = 'x';
        binary[1] = 0;
        invokeFileExpect("filetools.csv.clean", "binary.csv", binary, userBearer,
                new Expectation("processor_input_invalid", "魔数"));
        // 超大小（声明 5MB）
        byte[] big = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        invokeFileExpect("filetools.csv.clean", "big.csv", big, userBearer,
                new Expectation("processor_input_invalid", "大小"));
    }

    @Test
    void entityProcessorRejectsFileUpload() throws Exception {
        ensureActivated(ANALYTICS_DIR, "example.analytics");
        invokeFileExpect("analytics.purchase.monthly", "data.csv", SAMPLE_CSV, userBearer,
                new Expectation("validation_error", "实体输入模式"));
    }

    @Test
    void artifactAccessControlAndExpiry() throws Exception {
        String result = invokeFile("filetools.csv.clean", "data.csv", SAMPLE_CSV, userBearer);
        String artifactId = JsonPath.read(result, "$.artifactId");
        // 非本人非 ADMIN：404 防枚举
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/processors/artifacts/"
                        + artifactId + "/download")
                        .header("Authorization", developerBearer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("artifact_not_found"));
        // ADMIN 可下载
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/processors/artifacts/"
                        + artifactId + "/download")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        // 过期：读时兜底 410
        jdbc.update("UPDATE processor_artifact SET expires_at = now() - interval '1 minute'"
                + " WHERE id = ?", artifactId);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/processors/artifacts/"
                        + artifactId + "/download")
                        .header("Authorization", adminBearer))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("artifact_expired"));
    }

    @Test
    void badAcceptManifestIsRejected() throws Exception {
        byte[] zip = zipPackage(FILETOOLS_DIR,
                Map.of("plugin.json", new String(Files.readAllBytes(
                                FILETOOLS_DIR.resolve("plugin.json")), StandardCharsets.UTF_8)
                        .replace("\"txt\"", "\"exe\"")
                        .replace("\"version\": \"0.1.1\"", "\"version\": \"0.1.2\"")),
                        null);
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("accept")));
    }

    // ===== 调用与包构造助手 =====

    private String invokeFile(String key, String filename, byte[] payload, String bearer)
            throws Exception {
        return mockMvc.perform(multipartInvoke(key, filename, payload)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void invokeFileExpect(String key, String filename, byte[] payload, String bearer,
                                  Expectation expectation) throws Exception {
        mockMvc.perform(multipartInvoke(key, filename, payload)
                        .header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(expectation.code()))
                .andExpect(jsonPath("$.message").value(containsString(expectation.fragment())));
    }

    private record Expectation(String code, String fragment) {
    }

    private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder multipartInvoke(
            String key, String filename, byte[] payload) {
        return MockMvcRequestBuilders.multipart(
                        "/api/v1/plugins/processors/" + key + "/invoke-file")
                .file(new MockMultipartFile("file", filename, "application/octet-stream",
                        payload));
    }

    private void ensureActivated(Path dir, String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        List<String> activeIds = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        if (activeIds.isEmpty()) {
            byte[] zip = zipPackage(dir, Map.of(), null);
            String body = mockMvc.perform(
                            MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                    .file(new MockMultipartFile("file", "pkg.zip",
                                            "application/zip", zip))
                                    .header("Authorization", adminBearer))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            String versionId = JsonPath.read(body, "$.versionId");
            mockMvc.perform(MockMvcRequestBuilders.post(
                            "/api/v1/plugins/" + versionId + "/activate")
                            .header("Authorization", adminBearer))
                    .andExpect(status().isOk());
        }
    }

    private String getJson(String path, String bearer) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 打包插件目录；overrides 替换指定文件内容（P22 测试同手法）。 */
    private static byte[] zipPackage(Path packageDir, Map<String, String> overrides,
                                     String unused) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                String entry = packageDir.relativize(file).toString().replace('\\', '/');
                byte[] bytes = overrides.containsKey(entry)
                        ? overrides.get(entry).getBytes(StandardCharsets.UTF_8)
                        : Files.readAllBytes(file);
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
