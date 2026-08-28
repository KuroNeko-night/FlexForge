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
 * P07 验收（FR-PLUGIN-01/01A/02/08、RB-PLUGIN-VALID）：合法包导入与预览、
 * 幂等导入、同版本异内容拒绝、依赖缺失可诊断、unsupported_schema_version、
 * zip-slip/魔数拒绝、validate 不落库、权限矩阵。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PluginApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String developerBearer;

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        developerBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
    }

    static byte[] packageZip(String pluginId, String version, String dependencies,
                             String migrations) {
        String manifest = "{\"schemaVersion\":1,\"id\":\"" + pluginId + "\",\"name\":\"" + pluginId
                + "\",\"version\":\"" + version + "\",\"capabilityLevel\":1,"
                + "\"minPlatformVersion\":\"0.1.0\",\"dependencies\":" + dependencies + ","
                + "\"contributions\":{\"navigation\":[\"" + pluginId + ".items\"],"
                + "\"renderers\":[\"enum.default\"]},"
                + "\"resources\":{\"entities\":[\"metadata/entities/item.json\"],"
                + "\"views\":[],\"migrations\":" + migrations + "}}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("metadata/entities/item.json", "{\"name\":\"item\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("assets/logo.png", PNG_MAGIC);
        for (String script : migrations.replace("[", "").replace("]", "").replace("\"", "").split(",")) {
            String trimmed = script.trim();
            if (!trimmed.isEmpty()) {
                entries.put(trimmed, "CREATE TABLE inv_item (id INT);".getBytes(StandardCharsets.UTF_8));
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

    private String importOk(byte[] bytes, String pluginId, String version) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pluginId").value(pluginId))
                .andExpect(jsonPath("$.version").value(version))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.versionId");
    }

    @Test
    void validLevel1PackageImportsWithPreviewAndAudit() throws Exception {
        byte[] bytes = packageZip("demo.one", "1.0.0", "[]",
                "[\"migrations/V001__demo_one.sql\"]");
        String versionId = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNew").value(true))
                .andExpect(jsonPath("$.contentHash").isNotEmpty())
                .andExpect(jsonPath("$.scriptChecksums['migrations/V001__demo_one.sql']").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(versionId, "$.versionId");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT plugin_id, version, capability_level FROM plugin_version WHERE id = ?", id);
        assertThat(row.get("plugin_id")).isEqualTo("demo.one");
        String actor = jdbc.queryForObject(
                "SELECT actor FROM sys_audit_event WHERE action = 'plugin.import' AND object_id = ?",
                String.class, id);
        assertThat(actor).isEqualTo(AuthTestSupport.ADMIN_USERNAME);
    }

    @Test
    void duplicateImportIsIdempotentAndConflictRejected() throws Exception {
        byte[] original = packageZip("demo.two", "1.0.0", "[]", "[]");
        String firstId = importOk(original, "demo.two", "1.0.0");

        // 同内容重复导入：幂等命中，不产生新版本
        String second = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", original))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNew").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.versionId")).isEqualTo(firstId);
        Integer versions = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_version WHERE plugin_id = 'demo.two'", Integer.class);
        assertThat(versions).isEqualTo(1);

        // 同版本不同内容（checksum 变化）拒绝
        byte[] mutated = packageZip("demo.two", "1.0.0", "[]", "[\"migrations/V001__conflict.sql\"]");
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", mutated))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不一致")));
    }

    @Test
    void dependencyMissingNamesPluginAndRange() throws Exception {
        byte[] bytes = packageZip("demo.three", "1.0.0",
                "[{\"pluginId\":\"vendor.absent\",\"versionRange\":\"^1.0.0\"}]", "[]");
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("dependency_missing"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("vendor.absent")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("^1.0.0")));
    }

    @Test
    void dependencyOnImportedPluginResolves() throws Exception {
        importOk(packageZip("demo.dep.base", "1.2.0", "[]", "[]"), "demo.dep.base", "1.2.0");
        byte[] dependent = packageZip("demo.dep.child", "0.1.0",
                "[{\"pluginId\":\"demo.dep.base\",\"versionRange\":\"^1.0.0\"}]", "[]");
        importOk(dependent, "demo.dep.child", "0.1.0");
        Integer deps = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_dependency pd JOIN plugin_version pv"
                        + " ON pd.plugin_version_id = pv.id WHERE pv.plugin_id = 'demo.dep.child'",
                Integer.class);
        assertThat(deps).isEqualTo(1);
    }

    @Test
    void unsupportedSchemaVersionAndIllegalPackagesRejected() throws Exception {
        byte[] badSchema = replaceInPackage("demo.four", "1.0.0",
                pkg -> pkg.replace("\"schemaVersion\":1", "\"schemaVersion\":99"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", badSchema))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported_schema_version"));

        Map<String, byte[]> slip = new LinkedHashMap<>();
        slip.put("plugin.json", "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8));
        slip.put("../evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip(slip)))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_manifest"));

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                "plain text".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("魔数")));
    }

    interface PackageMutator {
        String mutate(String manifest);
    }

    private static byte[] replaceInPackage(String pluginId, String version, PackageMutator mutator) {
        String manifest = "{\"schemaVersion\":1,\"id\":\"" + pluginId + "\",\"name\":\"" + pluginId
                + "\",\"version\":\"" + version + "\",\"capabilityLevel\":1,"
                + "\"minPlatformVersion\":\"0.1.0\",\"dependencies\":[],"
                + "\"contributions\":{},\"resources\":{\"entities\":[],\"views\":[],\"migrations\":[]}}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", mutator.mutate(manifest).getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    @Test
    void validateDoesNotPersistAndReportsFindings() throws Exception {
        byte[] bytes = packageZip("demo.five", "1.0.0", "[]", "[]");
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/validate")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.preview.pluginId").value("demo.five"));
        Integer versions = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_version WHERE plugin_id = 'demo.five'", Integer.class);
        assertThat(versions).isZero();

        byte[] invalid = replaceInPackage("demo.five", "1.0.0",
                pkg -> pkg.replace("\"capabilityLevel\":1", "\"capabilityLevel\":3"));
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/validate")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", invalid))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.findings[0]").value(org.hamcrest.Matchers.containsString("capabilityLevel")));
    }

    @Test
    void onlyAdminMayUpload() throws Exception {
        byte[] bytes = packageZip("demo.six", "1.0.0", "[]", "[]");
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes))
                        .header("Authorization", developerBearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", bytes)))
                .andExpect(status().isUnauthorized());
    }
}
