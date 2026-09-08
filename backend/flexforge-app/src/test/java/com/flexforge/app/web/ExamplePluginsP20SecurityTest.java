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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P20 数据处理器安全边界验收（docs/09 P20 验收③，自 P20Test 拆分）：包边界
 * （Level 1 带 .py 拒、未声明脚本拒、声明缺失拒）、invoke 边界（实体不匹配、
 * 未知 key 专用码、停用失效）、处理器进程无环境凭据、输入行数超限（审查
 * P2-2/P2-3/P2-5 用例修复与新增）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExamplePluginsP20SecurityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Path ANALYTICS_DIR = Path.of("..", "..", "plugins", "example-analytics");

    private static byte[] analyticsZip;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;

    @BeforeAll
    static void buildPackages() throws Exception {
        analyticsZip = zipPackage(ANALYTICS_DIR);
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
    }

    @Test
    void packageSecurityBoundaries() throws Exception {
        // ① Level 1 包携带 .py 被拒（S6 双轨：manifest 声明 Level 1 且无 processors，包内有脚本）
        importRejected(level1PackageWithScript());
        // ② 完整包 + 未声明 scripts/extra.py（双向核对：包内必有声明）
        importRejected(zipWithExtraScript());
        // ③ 完整 manifest 但包内缺声明的脚本（双向核对：声明必在包内）
        importRejected(zipMissingDeclaredScript());
    }

    @Test
    void invokeSecurityBoundaries() throws Exception {
        ensureP19PluginsActivated();
        reactivateAnalyticsBase();
        // 实体不匹配：purchase 处理器传 production_order
        mockMvc.perform(invokePost("analytics.purchase.monthly")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"production_order\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
        // 未知 key → 404 + 专用码 processor_not_found（审查 P2-5）
        mockMvc.perform(invokePost("analytics.none")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"purchase_order\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("processor_not_found"));
        // 停用后 invoke → 404（撤销可验证，FR-PLUGIN-06）
        stopActive("example.analytics");
        mockMvc.perform(invokePost("analytics.quality.yield")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"quality_inspection\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("processor_not_found"));
    }

    @Test
    void processorProcessHasNoPlatformCredentials() throws Exception {
        ensureP19PluginsActivated();
        stopActive("example.analytics");
        // 处理器进程环境无平台凭据：脚本枚举敏感前缀键并计数，期望 0
        String probeScript = "import json, os\n"
                + "bad = [k for k in os.environ if k.startswith(('DB_', 'AUTH_', 'FLEXFORGE_'))]\n"
                + "print(json.dumps({'kind': 'summary', "
                + "'items': [{'label': 'env', 'value': len(bad)}]}))\n";
        String probeVersion = importOnly(scriptPackage(probeScript, "0.1.6"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + probeVersion + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        String probe = invokeOk("analytics.purchase.monthly", "purchase_order");
        List<Integer> envCounts = JsonPath.read(probe, "$.items[?(@.label == 'env')].value");
        assertThat(envCounts).containsExactly(0);
    }

    @Test
    void inputRowsBeyondPlatformPageCapRejected() throws Exception {
        ensureP19PluginsActivated();
        reactivateAnalyticsBase();
        // 造 201 条检验记录（> 平台单页上限 200 = 处理器输入行数上限，审查 P2-3）
        for (int i = 0; i < 201; i++) {
            postJson("/api/v1/data/quality_inspection", String.format(
                    "{\"material\":\"批量样本-%03d\",\"supplier\":\"压测供应商\",\"sample_qty\":1,"
                            + "\"defect_qty\":0,\"verdict\":\"合格\",\"full_check\":false,"
                            + "\"inspected_on\":\"2026-09-07\"}", i));
        }
        mockMvc.perform(invokePost("analytics.quality.yield")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"quality_inspection\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("processor_input_too_large"));
    }

    // ===== helpers =====

    private void importRejected(byte[] zip) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                        .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_manifest"));
    }

    private void ensureP19PluginsActivated() throws Exception {
        ensureActivated(Path.of("..", "..", "plugins", "example-purchase"), "example.purchase");
        ensureActivated(Path.of("..", "..", "plugins", "example-quality"), "example.quality");
        ensureActivated(Path.of("..", "..", "plugins", "example-workorder"), "example.workorder");
    }

    private void ensureActivated(Path dir, String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory");
        List<String> activeIds = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        if (activeIds.isEmpty()) {
            importAndActivate(zipPackage(dir));
        }
    }

    private String getJson(String path) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postJson(String path, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invokePost(
            String key) {
        return MockMvcRequestBuilders.post("/api/v1/plugins/processors/" + key + "/invoke");
    }

    private String invokeOk(String key, String entity) throws Exception {
        return mockMvc.perform(invokePost(key)
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"" + entity + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 回到 0.2.0 基线：先停占用，再激活清单中既有 versionId（字节不可变契约下
     * 重打包导入必 400；导入仅在该版本从未存在时发生——共享库类序不保证）。 */
    private void reactivateAnalyticsBase() throws Exception {
        stopActive("example.analytics");
        String inventory = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/plugins/inventory").header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String versionId = versionIdOf(inventory);
        if (versionId == null) {
            importAndActivate(analyticsZip);
            return;
        }
        mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
    }

    /** 从清单展开 0.2.0 版本号（Java 侧筛选，避免嵌套 JsonPath 过滤兼容性）。 */
    private static String versionIdOf(String inventory) {
        java.util.List<java.util.Map<String, Object>> versions =
                com.jayway.jsonpath.JsonPath.read(inventory,
                        "$[?(@.pluginId == 'example.analytics')].versions[*]");
        for (java.util.Map<String, Object> entry : versions) {
            if ("0.2.0".equals(entry.get("version"))) {
                return String.valueOf(entry.get("versionId"));
            }
        }
        return null;
    }

    private String importOnly(byte[] zip) throws Exception {
        String body = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.versionId");
    }

    private String importAndActivate(byte[] zip) throws Exception {
        String versionId = importOnly(zip);
        String activation = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(activation, "$.id");
    }

    private void stopActive(String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory");
        List<String> ids = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        for (String id : ids) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + id + "/stop")
                            .header("Authorization", adminBearer))
                    .andExpect(status().isOk());
        }
    }

    // ===== 包构造 =====

    private static byte[] scriptPackage(String scriptContent, String version) throws Exception {
        byte[] manifest = Files.readAllBytes(ANALYTICS_DIR.resolve("plugin.json"));
        byte[] mutated = new String(manifest, StandardCharsets.UTF_8)
                .replace("\"version\": \"0.2.0\"", "\"version\": \"" + version + "\"")
                .getBytes(StandardCharsets.UTF_8);
        return zipOf(new Entry("plugin.json", mutated),
                new Entry("scripts/purchase-monthly.py", scriptContent.getBytes(StandardCharsets.UTF_8)),
                new Entry("scripts/quality-yield.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/quality-yield.py"))),
                new Entry("scripts/workorder-load.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/workorder-load.py"))),
                new Entry("scripts/purchase-chart-monthly.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve(
                                "scripts/purchase-chart-monthly.py"))),
                new Entry("scripts/quality-chart-share.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve(
                                "scripts/quality-chart-share.py"))));
    }

    /** Level 1 声明（无 processors）+ 包内携带脚本：S6 双轨拒绝面。 */
    private static byte[] level1PackageWithScript() throws Exception {
        String manifest = """
                {
                  "schemaVersion": 1,
                  "id": "example.level1.script",
                  "name": "level1 带脚本坏包",
                  "version": "0.1.0",
                  "capabilityLevel": 1,
                  "minPlatformVersion": "0.1.0",
                  "dependencies": [],
                  "permissions": [],
                  "contributions": {},
                  "resources": {}
                }
                """;
        return zipOf(new Entry("plugin.json", manifest.getBytes(StandardCharsets.UTF_8)),
                new Entry("scripts/evil.py", "print('x')\n".getBytes(StandardCharsets.UTF_8)));
    }

    /** 完整合法包 + 未声明的 scripts/extra.py（审查 P2-2：整包重建，真实命中
     * requireScriptsDeclared 的"包内脚本未被声明"分支）。 */
    private static byte[] zipWithExtraScript() throws Exception {
        return zipOf(
                new Entry("plugin.json", Files.readAllBytes(ANALYTICS_DIR.resolve("plugin.json"))),
                new Entry("scripts/purchase-monthly.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/purchase-monthly.py"))),
                new Entry("scripts/quality-yield.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/quality-yield.py"))),
                new Entry("scripts/workorder-load.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/workorder-load.py"))),
                new Entry("scripts/purchase-chart-monthly.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve(
                                "scripts/purchase-chart-monthly.py"))),
                new Entry("scripts/quality-chart-share.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve(
                                "scripts/quality-chart-share.py"))),
                new Entry("scripts/extra.py",
                        "print('undeclared')\n".getBytes(StandardCharsets.UTF_8)));
    }

    /** 完整 manifest（声明三脚本）但包内缺 purchase-monthly.py——"声明的脚本在包内缺失"。 */
    private static byte[] zipMissingDeclaredScript() throws Exception {
        return zipOf(
                new Entry("plugin.json", Files.readAllBytes(ANALYTICS_DIR.resolve("plugin.json"))),
                new Entry("scripts/quality-yield.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/quality-yield.py"))),
                new Entry("scripts/workorder-load.py",
                        Files.readAllBytes(ANALYTICS_DIR.resolve("scripts/workorder-load.py"))));
    }

    private record Entry(String name, byte[] bytes) {
    }

    private static byte[] zipOf(Entry... entries) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Entry entry : entries) {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry.name()));
                zos.write(entry.bytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] zipPackage(Path packageDir) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                String entry = packageDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
