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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P20 数据处理器计算与失败路径验收（docs/09 P20 验收①②；安全边界见
 * ExamplePluginsP20SecurityTest）：三处理器对真实实体数据计算正确（透视/聚合/
 * 汇总），失败路径全测（超时真 kill/非零退出/非 JSON/结构违约/字段违约）且
 * 失败留审计（审查 P2-4）。宿主需有 python3/python。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExamplePluginsP20Test {

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
    private String userBearer;

    @BeforeAll
    static void buildPackages() throws Exception {
        analyticsZip = zipPackage(ANALYTICS_DIR);
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
    void processorsComputeRealEntityData() throws Exception {
        ensureP19PluginsActivated();
        seedEntityRecords();
        reactivateAnalyticsBase();

        // 清单端点（USER 可读，与数据读同权）：三处理器注册可见，按实体过滤生效
        String listBody = getJson("/api/v1/plugins/processors", userBearer);
        List<String> keys = JsonPath.read(listBody, "$[*].key");
        assertThat(keys).contains("analytics.purchase.monthly", "analytics.quality.yield",
                "analytics.workorder.load");
        String filtered = getJson("/api/v1/plugins/processors?entity=purchase_order", userBearer);
        List<String> entities = JsonPath.read(filtered, "$[*].inputEntity");
        assertThat(entities).containsOnly("purchase_order");

        // ① 采购月度透视：行=供应商、列=到货月份、值=金额合计（种子 2000×9.30=18600.0）
        String pivot = invokeOk("analytics.purchase.monthly", "purchase_order", userBearer);
        assertThat(pivot).contains("2026-09");
        List<Object> firstRow = JsonPath.read(pivot, "$.rows[0]");
        assertThat(firstRow).contains(18600.0);

        // ② 检验合格率：按供应商聚合（华东 1 合格 1 不合格 → 50.0%）
        String yield = invokeOk("analytics.quality.yield", "quality_inspection", userBearer);
        List<String> columnNames = JsonPath.read(yield, "$.columns[*].name");
        assertThat(columnNames).contains("supplier", "pass_rate", "defect_qty");
        assertThat(yield).contains("50.0%");

        // ③ 工单负载汇总：summary 形态（状态计数与计划量）
        String load = invokeOk("analytics.workorder.load", "production_order", adminBearer);
        List<String> itemLabels = JsonPath.read(load, "$.items[*].label");
        assertThat(itemLabels).contains("计划数量合计", "最高负载班组");
    }

    @Test
    void processorFailurePathsAreRejectedWithStableCodes() throws Exception {
        ensureP19PluginsActivated();
        // 坏包各用独立版本号（同版本异内容被字节不可变契约拒绝）
        expectFailure("import time\ntime.sleep(30)\n", "0.1.1", "processor_failed", "超时");
        expectFailure("import sys\nsys.exit(3)\n", "0.1.2", "processor_failed", "退出码");
        expectFailure("print('not-json')\n", "0.1.3", "processor_output_invalid", null);
        expectFailure("import json\nprint(json.dumps({'foo': 1}))\n", "0.1.4",
                "processor_output_invalid", null);
        expectFailure("import json\nprint(json.dumps({'kind': 'table'}))\n", "0.1.5",
                "processor_output_invalid", null);
        // 失败也留审计痕（docs/09 P20 验收②，审查 P2-4）
        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'plugin.processor.invoke'"
                        + " AND result = 'failure'", Integer.class);
        assertThat(failures).isGreaterThanOrEqualTo(5);
    }

    // ===== 数据与断言 helpers =====

    private void ensureP19PluginsActivated() throws Exception {
        ensureActivated(Path.of("..", "..", "plugins", "example-purchase"), "example.purchase");
        ensureActivated(Path.of("..", "..", "plugins", "example-quality"), "example.quality");
        ensureActivated(Path.of("..", "..", "plugins", "example-workorder"), "example.workorder");
    }

    /** 回到 0.2.0 基线：先停占用，再激活清单中既有 versionId——重打包字节漂移
     * 会撞同版本不可变契约，导入仅在该版本从未存在时发生（共享库类序不保证）。 */
    private void reactivateAnalyticsBase() throws Exception {
        stopActive("example.analytics");
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        String versionId = versionIdOf(inventory, "0.2.0");
        if (versionId == null) {
            importAndActivate(analyticsZip);
            return;
        }
        mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
    }

    private void seedEntityRecords() throws Exception {
        postJson("/api/v1/data/purchase_order",
                "{\"code\":\"PO-P20-1\",\"supplier\":\"华东精密配件\",\"material\":\"轴承 6204-2RS\","
                        + "\"qty\":2000,\"amount\":18600.00,\"status\":\"部分到货\","
                        + "\"expected_on\":\"2026-09-25\"}");
        postJson("/api/v1/data/purchase_order",
                "{\"code\":\"PO-P20-2\",\"supplier\":\"南方铝业\",\"material\":\"铝型材 4040\","
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
        postJson("/api/v1/data/production_order",
                "{\"code\":\"WO-P20-1\",\"product\":\"精密主轴组件\",\"plan_qty\":40,"
                        + "\"status\":\"执行中\",\"team\":\"装配一组\",\"due_on\":\"2026-09-20\"}");
        postJson("/api/v1/data/production_order",
                "{\"code\":\"WO-P20-2\",\"product\":\"伺服驱动外壳\",\"plan_qty\":200,"
                        + "\"status\":\"计划\",\"team\":\"注塑班组\",\"due_on\":\"2026-09-28\"}");
    }

    private String getJson(String path, String bearer) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postJson(String path, String body) throws Exception {
        return postJson(path, adminBearer, body);
    }

    private String postJson(String path, String bearer, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder invokePost(
            String key) {
        return MockMvcRequestBuilders.post("/api/v1/plugins/processors/" + key + "/invoke");
    }

    private String invokeOk(String key, String entity, String bearer) throws Exception {
        return mockMvc.perform(invokePost(key)
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"" + entity + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void expectFailure(String scriptContent, String version, String expectedCode,
                               String messageFragment) throws Exception {
        String versionId = importOnly(scriptPackage(scriptContent, version));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        String body = mockMvc.perform(invokePost("analytics.purchase.monthly")
                        .header("Authorization", adminBearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"purchase_order\"}"))
                .andExpect(status().is5xxServerError())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(expectedCode))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (messageFragment != null) {
            assertThat(body).contains(messageFragment);
        }
        stopActive("example.analytics");
    }

    /** 从清单展开目标版本号（Java 侧筛选，避免嵌套 JsonPath 过滤兼容性）。 */
    private static String versionIdOf(String inventory, String version) {
        List<java.util.Map<String, Object>> versions = JsonPath.read(inventory,
                "$[?(@.pluginId == 'example.analytics')].versions[*]");
        for (java.util.Map<String, Object> entry : versions) {
            if (version.equals(entry.get("version"))) {
                return String.valueOf(entry.get("versionId"));
            }
        }
        return null;
    }

    private void ensureActivated(Path dir, String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        List<String> activeIds = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        if (activeIds.isEmpty()) {
            importAndActivate(zipPackage(dir));
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
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(activation, "$.id");
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

    // ===== 包构造 =====

    private static byte[] scriptPackage(String scriptContent, String version) throws Exception {
        byte[] manifest = Files.readAllBytes(ANALYTICS_DIR.resolve("plugin.json"));
        byte[] mutated = new String(manifest, StandardCharsets.UTF_8)
                .replace("\"version\": \"0.2.0\"", "\"version\": \"" + version + "\"")
                .getBytes(StandardCharsets.UTF_8);
        // P22 起 manifest 声明五个脚本（含双图处理器）：坏包须带全声明脚本（导入双向核对）
        return zipOf(new Entry("plugin.json", mutated),
                new Entry("scripts/purchase-monthly.py",
                        scriptContent.getBytes(StandardCharsets.UTF_8)),
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
