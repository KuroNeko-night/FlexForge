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
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P27 多样化处理器验收（docs/09 P27 实施B）：三个新 Level 2 包（交叉矩阵/金额
 * 直方图/热门物料榜）对真实实体数据计算正确，清单按实体过滤可见；输出形态
 * 与既有透视/占比/负载/文件处理器互补。失败路径契约由 P20/P22 既有测试覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExampleProcessorsP27Test {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Path PURCHASE_DIR = Path.of("..", "..", "plugins", "example-purchase");
    private static final Path WORKORDER_DIR = Path.of("..", "..", "plugins", "example-workorder");
    private static final Path CROSSTAB_DIR = Path.of("..", "..", "plugins", "example-crosstab");
    private static final Path HISTOGRAM_DIR = Path.of("..", "..", "plugins", "example-histogram");
    private static final Path TOPITEMS_DIR = Path.of("..", "..", "plugins", "example-topitems");

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
    void diverseProcessorsComputeRealEntityData() throws Exception {
        activatePluginsAndSeed();
        assertProcessorListVisibility();

        // ① 交叉矩阵：行=产品、列=状态、值=计数
        String matrix = invokeOk("crosstab.workorder.matrix", "production_order", userBearer);
        assertMatrixShape(matrix);
        // ② 金额直方图：bar chart，5 档分箱
        String histogram = invokeOk("histogram.purchase.amount", "purchase_order", userBearer);
        assertHistogramShape(histogram);
        // ③ 热门物料榜：bar chart，频次降序
        String top = invokeOk("topitems.purchase.material", "purchase_order", userBearer);
        assertTopShape(top);
    }

    private void assertProcessorListVisibility() throws Exception {
        String listBody = getJson("/api/v1/plugins/processors", userBearer);
        List<String> keys = JsonPath.read(listBody, "$[*].key");
        assertThat(keys).contains("crosstab.workorder.matrix", "histogram.purchase.amount",
                "topitems.purchase.material");
        String filtered = getJson("/api/v1/plugins/processors?entity=production_order", userBearer);
        List<String> entities = JsonPath.read(filtered, "$[*].inputEntity");
        assertThat(entities).containsOnly("production_order");
    }

    private static void assertMatrixShape(String matrix) {
        List<String> columnNames = JsonPath.read(matrix, "$.columns[*].name");
        assertThat(columnNames).contains("product", "执行中", "计划");
        List<List<Object>> rows = JsonPath.read(matrix, "$.rows");
        assertThat(rows.toString()).contains("轴承 6204").contains("伺服驱动外壳");
    }

    private static void assertHistogramShape(String histogram) {
        assertThat(histogram).contains("\"kind\":\"chart\"").contains("\"chartType\":\"bar\"");
        List<String> bins = JsonPath.read(histogram, "$.categories");
        assertThat(bins).containsExactly("0-1k", "1k-5k", "5k-10k", "10k-50k", "50k+");
        List<Integer> counts = JsonPath.read(histogram, "$.values");
        assertThat(counts.get(0)).isGreaterThanOrEqualTo(1);
        assertThat(counts.get(3)).isGreaterThanOrEqualTo(1);
    }

    private static void assertTopShape(String top) {
        assertThat(top).contains("\"kind\":\"chart\"").contains("轴承 6204");
        List<Integer> values = JsonPath.read(top, "$.values");
        assertThat(values).isSortedAccordingTo(Comparator.reverseOrder());
    }

    private void activatePluginsAndSeed() throws Exception {
        ensureActivatedByDir(PURCHASE_DIR, "example.purchase");
        ensureActivatedByDir(WORKORDER_DIR, "example.workorder");
        ensureActivatedByDir(CROSSTAB_DIR, "example.crosstab");
        ensureActivatedByDir(HISTOGRAM_DIR, "example.histogram");
        ensureActivatedByDir(TOPITEMS_DIR, "example.topitems");
        postJson("/api/v1/data/purchase_order",
                "{\"code\":\"PO-P27-1\",\"supplier\":\"演示供应商\",\"material\":\"轴承 6204\","
                        + "\"qty\":100,\"amount\":500.00,\"status\":\"已下单\","
                        + "\"expected_on\":\"2026-10-01\"}");
        postJson("/api/v1/data/purchase_order",
                "{\"code\":\"PO-P27-2\",\"supplier\":\"演示供应商\",\"material\":\"轴承 6204\","
                        + "\"qty\":50,\"amount\":47200.50,\"status\":\"部分到货\","
                        + "\"expected_on\":\"2026-10-15\"}");
        postJson("/api/v1/data/production_order",
                "{\"code\":\"WO-P27-1\",\"product\":\"轴承 6204\",\"plan_qty\":10,"
                        + "\"status\":\"执行中\",\"team\":\"装配一组\",\"due_on\":\"2026-10-20\"}");
        postJson("/api/v1/data/production_order",
                "{\"code\":\"WO-P27-2\",\"product\":\"轴承 6204\",\"plan_qty\":20,"
                        + "\"status\":\"执行中\",\"team\":\"装配二组\",\"due_on\":\"2026-10-22\"}");
        postJson("/api/v1/data/production_order",
                "{\"code\":\"WO-P27-3\",\"product\":\"伺服驱动外壳\",\"plan_qty\":5,"
                        + "\"status\":\"计划\",\"team\":\"注塑班组\",\"due_on\":\"2026-10-25\"}");
    }

    private void ensureActivatedByDir(Path dir, String pluginId) throws Exception {
        String inventory = getJson("/api/v1/plugins/inventory", adminBearer);
        List<String> activeIds = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        if (!activeIds.isEmpty()) {
            return;
        }
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                        .file(new MockMultipartFile("file", "pkg.zip",
                                "application/zip", zipPackage(dir)))
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        List<String> versionIds = JsonPath.read(
                getJson("/api/v1/plugins/inventory", adminBearer),
                "$[?(@.pluginId == '" + pluginId + "')].versions[*].versionId");
        mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/plugins/" + versionIds.get(versionIds.size() - 1) + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
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
        return mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/plugins/processors/" + key + "/invoke")
                        .header("Authorization", bearer)
                        .contentType("application/json")
                        .content("{\"entity\": \"" + entity + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 目录打包（确定性条目序：Files.walk 排序；与 E2e 夹具同一形态）。 */
    private static byte[] zipPackage(Path packageDir) throws java.io.IOException {
        List<Path> files = new java.util.ArrayList<>();
        try (var walk = java.nio.file.Files.walk(packageDir)) {
            walk.filter(java.nio.file.Files::isRegularFile).sorted().forEach(files::add);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                String entry = packageDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.write(java.nio.file.Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
