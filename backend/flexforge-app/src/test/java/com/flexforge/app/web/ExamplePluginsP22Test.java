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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P22 图表处理器验收（docs/09 P22，FR-CHART-01/FR-PLUGIN-13）：example-analytics
 * 0.2.4 两图处理器对真实实体数据计算正确（bar 月度金额/pie 结论占比），坏 chart
 * 输出三向（缺 chartType/values 不等长/pie 负值）统一 processor_output_invalid。
 * 宿主需有 python3/python。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExamplePluginsP22Test {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Path ANALYTICS_DIR = Path.of("..", "..", "plugins", "example-analytics");

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
    void chartProcessorsComputeRealEntityData() throws Exception {
        ensureActivated(Path.of("..", "..", "plugins", "example-purchase"), "example.purchase");
        ensureActivated(Path.of("..", "..", "plugins", "example-quality"), "example.quality");
        seedEntityRecords();
        // 共享库类序/方法序不保证：先停旧或坏版本，再回到 0.2.4 基线
        ensureVersionActivated("example.analytics", "0.2.4");

        // 清单：两图处理器按目标实体过滤可见
        String filtered = getJson("/api/v1/plugins/processors?entity=purchase_order", userBearer);
        List<String> keys = JsonPath.read(filtered, "$[*].key");
        assertThat(keys).contains("analytics.purchase.chart_monthly");

        // ① 条形图：按到货月份合计金额（18600.00 + 47200.50）
        String bar = invokeOk("analytics.purchase.chart_monthly", "purchase_order", userBearer);
        assertThat(bar).contains("\"kind\":\"chart\"");
        assertThat(bar).contains("\"chartType\":\"bar\"");
        List<String> categories = JsonPath.read(bar, "$.categories");
        assertThat(categories).containsExactly("2026-09", "2026-10");
        List<Double> values = JsonPath.read(bar, "$.values");
        assertThat(values).containsExactly(18600.0, 47200.5);

        // ② 饼图：检验结论占比（合格 1 / 不合格 1）
        String pie = invokeOk("analytics.quality.chart_share", "quality_inspection", userBearer);
        assertThat(pie).contains("\"chartType\":\"pie\"");
        List<String> verdicts = JsonPath.read(pie, "$.categories");
        assertThat(verdicts).containsExactly("不合格", "合格");
        List<Integer> counts = JsonPath.read(pie, "$.values");
        assertThat(counts).containsExactly(1, 1);
    }

    @Test
    void malformedChartOutputsAreRejectedWithStableCode() throws Exception {
        // 实体来自业务插件（invoke 输入组装先查实体，缺实体是 404 而非输出校验路径）
        ensureActivated(Path.of("..", "..", "plugins", "example-purchase"), "example.purchase");
        ensureActivated(Path.of("..", "..", "plugins", "example-quality"), "example.quality");
        // 无需基线在激活态：每个坏包自带全量声明脚本，逐包停旧→激活→invoke
        expectChartFailure("0.2.1", "scripts/purchase-chart-monthly.py",
                "import json\nprint(json.dumps({'kind': 'chart', 'title': 't',"
                        + " 'categories': ['a'], 'values': [1]}))\n",
                "chart.chartType 必须是 bar 或 pie");
        expectChartFailure("0.2.2", "scripts/purchase-chart-monthly.py",
                "import json\nprint(json.dumps({'kind': 'chart', 'chartType': 'bar',"
                        + " 'title': 't', 'categories': ['a', 'b'], 'values': [1]}))\n",
                "等长");
        expectChartFailure("0.2.3", "scripts/quality-chart-share.py",
                "import json\nprint(json.dumps({'kind': 'chart', 'chartType': 'pie',"
                        + " 'title': 't', 'categories': ['a'], 'values': [-1]}))\n",
                "饼图份额不允许负值");
    }

    // ===== 数据与断言 helpers =====

    private void seedEntityRecords() throws Exception {
        postJson("/api/v1/data/purchase_order",
                "{\"code\":\"PO-P22-1\",\"supplier\":\"华东精密配件\",\"material\":\"轴承 6204-2RS\","
                        + "\"qty\":2000,\"amount\":18600.00,\"status\":\"部分到货\","
                        + "\"expected_on\":\"2026-09-25\"}");
        postJson("/api/v1/data/purchase_order",
                "{\"code\":\"PO-P22-2\",\"supplier\":\"南方铝业\",\"material\":\"铝型材 4040\","
                        + "\"qty\":800,\"amount\":47200.50,\"status\":\"已下单\","
                        + "\"expected_on\":\"2026-10-10\"}");
        postJson("/api/v1/data/quality_inspection",
                "{\"material\":\"轴承 6204-2RS\",\"supplier\":\"华东精密配件\",\"sample_qty\":125,"
                        + "\"defect_qty\":0,\"verdict\":\"合格\",\"full_check\":false,"
                        + "\"inspected_on\":\"2026-09-01\"}");
        postJson("/api/v1/data/quality_inspection",
                "{\"material\":\"密封圈 NBR70\",\"supplier\":\"华东精密配件\",\"sample_qty\":80,"
                        + "\"defect_qty\":12,\"verdict\":\"不合格\",\"full_check\":false,"
                        + "\"inspected_on\":\"2026-09-02\"}");
    }

    private String getJson(String path, String bearer) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", bearer))
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

    private String invokeOk(String key, String entity, String bearer) throws Exception {
        return mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/processors/" + key + "/invoke")
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content("{\"entity\": \"" + entity + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 坏 chart 包（版本 bump + 目标脚本替换）→ 激活 → invoke 断言稳定码与消息片段。 */
    private void expectChartFailure(String version, String scriptName, String scriptContent,
                                    String messageFragment) throws Exception {
        stopActive("example.analytics");
        String versionId = importOnly(mutatedPackage(version, scriptName, scriptContent));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        String key = scriptName.contains("quality") ? "analytics.quality.chart_share"
                : "analytics.purchase.chart_monthly";
        mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/processors/" + key + "/invoke")
                                .header("Authorization", adminBearer)
                                .contentType("application/json")
                                .content("{\"entity\": \""
                                        + (scriptName.contains("quality") ? "quality_inspection"
                                        : "purchase_order") + "\"}"))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("processor_output_invalid"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(messageFragment)));
    }

    private void ensureActivated(Path dir, String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        List<String> activeIds = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        if (activeIds.isEmpty()) {
            importAndActivate(zipPackage(dir, Map.of(), null));
        }
    }

    /** 回到指定基线版本：先停占用，再激活清单中该版本的既有 versionId——
     * 重打包 zip 字节漂移会撞同版本不可变契约，导入仅在该版本从未存在时发生。 */
    private void ensureVersionActivated(String pluginId, String version) throws Exception {
        stopActive(pluginId);
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        List<String> versionIds = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].versions[?(@.version == '" + version
                        + "')].versionId");
        if (versionIds.isEmpty()) {
            importAndActivate(zipPackage(ANALYTICS_DIR, Map.of(), null));
            return;
        }
        mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/plugins/" + versionIds.get(0) + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
    }

    private void stopActive(String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        List<String> ids = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        for (String id : ids) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + id + "/stop")
                            .header("Authorization", adminBearer))
                    .andExpect(status().isOk());
        }
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

    // ===== 包构造 =====

    /** 打包插件目录；scriptOverrides 替换指定脚本内容，version 非空时 bump 清单版本。 */
    private static byte[] zipPackage(Path packageDir, Map<String, String> scriptOverrides,
                                     String version) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                String entry = packageDir.relativize(file).toString().replace('\\', '/');
                byte[] bytes = Files.readAllBytes(file);
                String override = scriptOverrides.get(entry);
                if (override != null) {
                    bytes = override.getBytes(StandardCharsets.UTF_8);
                }
                if (version != null && entry.endsWith("plugin.json")) {
                    bytes = bumpVersion(bytes, version);
                }
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] mutatedPackage(String version, String scriptName, String scriptContent)
            throws java.io.IOException {
        return zipPackage(ANALYTICS_DIR, Map.of(scriptName, scriptContent), version);
    }

    private static byte[] bumpVersion(byte[] manifest, String version) {
        String text = new String(manifest, StandardCharsets.UTF_8);
        int start = text.indexOf("\"version\": \"");
        int from = start + "\"version\": \"".length();
        int end = text.indexOf('"', from);
        return (text.substring(0, from) + version + text.substring(end))
                .getBytes(StandardCharsets.UTF_8);
    }
}
