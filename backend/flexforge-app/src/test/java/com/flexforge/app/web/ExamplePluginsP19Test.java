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
 * P19 生产企业插件矩阵验收（docs/09 P19）：4 个纯声明 Level 1 包（quality/
 * workorder/purchase/safety）经同一注册链路激活——菜单、台账表迁移、视图契约
 * （workorder/safety 声明 kanban 且 groupBy=status）；业务记录经 data_record
 * 往返覆盖 integer/decimal/boolean/date/enum 字段契约；拖拽换列消费的补丁语义
 * （PATCH 只传分组字段，其余字段值保留）在 workorder 上验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExamplePluginsP19Test {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 类级单例包（zip 时间戳字节漂移会使 contentHash 变化，须只构建一次）。 */
    private static byte[] qualityZip;
    private static byte[] workorderZip;
    private static byte[] purchaseZip;
    private static byte[] safetyZip;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeAll
    static void buildPackages() throws Exception {
        qualityZip = zipPackage(Path.of("..", "..", "plugins", "example-quality"));
        workorderZip = zipPackage(Path.of("..", "..", "plugins", "example-workorder"));
        purchaseZip = zipPackage(Path.of("..", "..", "plugins", "example-purchase"));
        safetyZip = zipPackage(Path.of("..", "..", "plugins", "example-safety"));
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
    void manufacturingMatrixRegistersAndServesContracts() throws Exception {
        assertThat(importAndActivate(qualityZip)).isNotBlank();
        assertThat(importAndActivate(workorderZip)).isNotBlank();
        assertThat(importAndActivate(purchaseZip)).isNotBlank();
        assertThat(importAndActivate(safetyZip)).isNotBlank();

        // 菜单四入口对普通用户可见（navigation 贡献注册）
        assertThat(menuKeys(mockMvc, userBearer)).contains(
                "example.quality.inspections",
                "example.workorder.orders",
                "example.purchase.orders",
                "example.safety.hazards");

        // 台账表迁移落地（docs/07 迁移对象前缀契约）+ 幂等种子已插入
        for (String table : List.of("example_quality_inspection", "example_production_order",
                "example_purchase_order", "example_safety_hazard")) {
            Integer created = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                    Integer.class, table);
            assertThat(created).as("台账表 %s 应由插件迁移创建", table).isEqualTo(1);
        }
        Integer seeds = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM example_quality_inspection)"
                        + " + (SELECT count(*) FROM example_production_order)"
                        + " + (SELECT count(*) FROM example_purchase_order)"
                        + " + (SELECT count(*) FROM example_safety_hazard)", Integer.class);
        assertThat(seeds).isEqualTo(13);

        // 视图契约：workorder/safety 下发 kanban 且 groupBy=status（前端拖拽消费面）
        assertThat(kanbanGroupBy("production_order")).containsExactly("status");
        assertThat(kanbanGroupBy("safety_hazard")).containsExactly("status");
        // 表格型插件不带 kanban 视图（声明即得的反面：未声明不下发）
        assertThat(kanbanGroupBy("quality_inspection")).isEmpty();
        assertThat(kanbanGroupBy("purchase_order")).isEmpty();

        qualityCoversIntegerBooleanAndDate();
        purchaseCoversDecimal();
        workorderPatchMovesStatusOnly();
        safetyCoversEnumRoundTrip();
    }

    /** quality：integer 上下限键 + boolean 可选字段 + enum/date 往返。 */
    private void qualityCoversIntegerBooleanAndDate() throws Exception {
        String created = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/data/quality_inspection")
                                .header("Authorization", userBearer)
                                .contentType("application/json")
                                .content("{\"material\":\"伺服电机 STM-40\",\"supplier\":\"东莞电驱\","
                                        + "\"sample_qty\":50,\"defect_qty\":2,"
                                        + "\"verdict\":\"让步接收\",\"full_check\":true,"
                                        + "\"inspected_on\":\"2026-09-04\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/quality_inspection/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sample_qty").value(50))
                .andExpect(jsonPath("$.data.defect_qty").value(2))
                .andExpect(jsonPath("$.data.full_check").value(true))
                .andExpect(jsonPath("$.data.verdict").value("让步接收"));

        // integer 规则失败路径：抽样数量低于 min=1 被拒（声明式校验兜底）
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/quality_inspection")
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"material\":\"坏样本\",\"sample_qty\":0,\"defect_qty\":0,"
                                + "\"verdict\":\"合格\",\"inspected_on\":\"2026-09-04\"}"))
                .andExpect(status().isBadRequest());
    }

    /** purchase：decimal NUMERIC(20,6) 精度往返（P19 首个 decimal 业务示例插件）。 */
    private void purchaseCoversDecimal() throws Exception {
        String created = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/data/purchase_order")
                                .header("Authorization", userBearer)
                                .contentType("application/json")
                                .content("{\"code\":\"PO-T-2609-100\",\"supplier\":\"华东精密配件\","
                                        + "\"material\":\"轴承 6204-2RS\",\"qty\":300,"
                                        + "\"amount\":2790.50,\"status\":\"已下单\","
                                        + "\"expected_on\":\"2026-09-30\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/purchase_order/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(2790.5))
                .andExpect(jsonPath("$.data.status").value("已下单"));
    }

    /** workorder：拖拽换列消费的补丁语义——PATCH 只传分组字段，其余字段保留原值。 */
    private void workorderPatchMovesStatusOnly() throws Exception {
        String created = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/data/production_order")
                                .header("Authorization", userBearer)
                                .contentType("application/json")
                                .content("{\"code\":\"WO-T-2609-100\",\"product\":\"精密主轴组件\","
                                        + "\"plan_qty\":40,\"status\":\"计划\","
                                        + "\"team\":\"装配一组\",\"due_on\":\"2026-09-20\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/data/production_order/" + id)
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"status\":\"执行中\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("执行中"))
                .andExpect(jsonPath("$.data.plan_qty").value(40))
                .andExpect(jsonPath("$.data.team").value("装配一组"))
                .andExpect(jsonPath("$.data.due_on").value("2026-09-20"));
    }

    /** safety：enum 双维度（等级/整改状态）与看板分组字段的状态迁移往返。 */
    private void safetyCoversEnumRoundTrip() throws Exception {
        String created = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/data/safety_hazard")
                                .header("Authorization", userBearer)
                                .contentType("application/json")
                                .content("{\"summary\":\"测试通道线缆裸露\",\"location\":\"总装车间二层\","
                                        + "\"level\":\"一般\",\"status\":\"待整改\","
                                        + "\"owner\":\"小周\",\"deadline_on\":\"2026-09-15\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/data/safety_hazard/" + id)
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"status\":\"已闭环\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("已闭环"))
                .andExpect(jsonPath("$.data.level").value("一般"));
    }

    /** 实体详情契约中 kanban 视图的 groupBy 集合（无 kanban 视图时为空）。 */
    private List<String> kanbanGroupBy(String entity) throws Exception {
        String detail = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/" + entity)
                                .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(detail, "$.views[?(@.viewType == 'kanban')].groupBy");
    }

    private String importAndActivate(byte[] zip) throws Exception {
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");
        String activation = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(activation, "$.id");
    }

    /** 打包仓库插件目录（纯声明包：无 spec 替换/版本 bump 需求）。 */
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
