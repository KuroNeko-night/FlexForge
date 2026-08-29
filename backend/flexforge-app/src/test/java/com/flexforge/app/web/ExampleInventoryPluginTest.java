package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RB-PLUGIN-LIFE 预置包用例（docs/09 P09、FR-DEMO-01..03）：仓库内
 * plugins/example-inventory 以普通 zip 经标准 API 安装——与第三方同权、
 * 干净骨架无库存痕迹（NFR-SKEL-01）、普通用户 CRUD+非负规则（FR-DEMO-02）、
 * 停用/启用/卸载行为与审计保留。共享容器状态按 @Order 前后依赖
 * （1 干净断言 → 2 安装 → 3 CRUD → 4 停用/启用/卸载）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExampleInventoryPluginTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String NAV_KEY = "example.inventory.items";

    /** 类级单例包（zip 时间戳导致的字节漂移会使 contentHash 变化，须只构建一次）。 */
    private static byte[] packageZip;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeAll
    static void buildPackage() throws Exception {
        packageZip = zipPackage(Path.of("..", "..", "plugins", "example-inventory"));
    }

    @BeforeEach
    void seedAndLogin() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        adminBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.ADMIN_USERNAME, AuthTestSupport.adminPassword());
        userBearer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
    }

    // ===== NFR-SKEL-01：干净骨架无库存菜单/实体 =====
    @Test
    @Order(1)
    void cleanSkeletonHasNoInventoryTraces() throws Exception {
        assertThat(menuKeys(mockMvc, userBearer)).doesNotContain(NAV_KEY);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/inventory_item")
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());
        Integer entities = jdbc.queryForObject(
                "SELECT count(*) FROM meta_entity WHERE name = 'inventory_item'", Integer.class);
        assertThat(entities).isZero();
    }

    // ===== FR-DEMO-01/03：标准 API 安装，注册导航/实体/视图，与第三方同权 =====
    @Test
    @Order(2)
    void installViaStandardApiRegistersNavigationEntityAndViews() throws Exception {
        String activationId = importAndActivate();
        assertThat(activationId).isNotBlank();

        assertThat(menuKeys(mockMvc, adminBearer)).contains(NAV_KEY);
        assertThat(menuKeys(mockMvc, userBearer)).contains(NAV_KEY);

        // 实体对普通用户可见且四字段齐备（FR-DEMO-01）
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/by-name/inventory_item")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.length()").value(4))
                .andExpect(jsonPath("$.fields[2].name").value("qty"));

        // 视图注册（list/form 各一）
        Integer views = jdbc.queryForObject(
                "SELECT count(*) FROM meta_view v JOIN meta_entity e ON v.entity_id = e.id"
                        + " WHERE e.name = 'inventory_item'", Integer.class);
        assertThat(views).isEqualTo(2);

        // 迁移与可重复种子（V001 建表 + V002 三行）
        Integer tableExists = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_name = 'example_inventory_item'", Integer.class);
        assertThat(tableExists).isEqualTo(1);
        Integer seeds = jdbc.queryForObject(
                "SELECT count(*) FROM example_inventory_item", Integer.class);
        assertThat(seeds).isEqualTo(3);

        // inventory 清单（Issue #20 第 2 项消费方）：非管理员拒绝
        assertInventoryListedActiveForAdmin();
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/inventory")
                        .header("Authorization", userBearer))
                .andExpect(status().isForbidden());
    }

    private void assertInventoryListedActiveForAdmin() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/plugins/inventory")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pluginId").value("example.inventory"))
                .andExpect(jsonPath("$[0].instanceStatus").value("active"))
                .andExpect(jsonPath("$[0].versions.length()").value(1))
                .andExpect(jsonPath("$[0].activations[0].status").value("ACTIVE"));
    }

    // ===== FR-DEMO-02：普通用户查询/编辑 + 非负规则 =====
    @Test
    @Order(3)
    void userCrudEnforcesNonNegativeQuantity() throws Exception {
        importAndActivate();
        String created = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/inventory_item")
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"sku\":\"BOLT-M6\",\"name\":\"M6 螺栓\",\"qty\":10,\"status\":\"在库\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/inventory_item")
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"sku\":\"BAD\",\"name\":\"坏数据\",\"qty\":-1,\"status\":\"在库\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/data/inventory_item/" + id)
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"qty\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qty").value(25));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/inventory_item?pageSize=10")
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // ===== 停用/启用/卸载：菜单、数据与审计行为（RB-PLUGIN-LIFE 无残留）=====
    @Test
    @Order(4)
    void stopReEnableAndUninstallBehavePerContract() throws Exception {
        String activationId = importAndActivate();
        seedOneRecord();

        stopAndAssertRemoved(activationId);
        reEnableAndAssertDataPreserved();
        uninstallAndAssertAuditRetained();
    }

    private void seedOneRecord() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/inventory_item")
                        .header("Authorization", userBearer)
                        .contentType("application/json")
                        .content("{\"sku\":\"KEEP-1\",\"name\":\"保留数据\",\"qty\":3,\"status\":\"在库\"}"))
                .andExpect(status().isOk());
    }

    // 停用：菜单消失、数据 API 404
    private void stopAndAssertRemoved(String activationId) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + activationId + "/stop")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        assertThat(menuKeys(mockMvc, userBearer)).doesNotContain(NAV_KEY);
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/inventory_item")
                        .header("Authorization", userBearer))
                .andExpect(status().isNotFound());
    }

    // 启用（再次激活）：菜单与数据回归（data_record 保留）
    private void reEnableAndAssertDataPreserved() throws Exception {
        String versionId = jdbc.queryForObject(
                "SELECT id FROM plugin_version WHERE plugin_id = 'example.inventory'",
                String.class);
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        assertThat(menuKeys(mockMvc, userBearer)).contains(NAV_KEY);
        String listBody = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/data/inventory_item?pageSize=50")
                                .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> skus = JsonPath.read(listBody, "$.items[*].data.sku");
        assertThat(skus).contains("KEEP-1");
    }

    // 卸载：注册全撤销、实体 disabled、审计保留
    private void uninstallAndAssertAuditRetained() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/plugins/example.inventory")
                        .header("Authorization", adminBearer))
                .andExpect(status().isOk());
        assertThat(menuKeys(mockMvc, userBearer)).doesNotContain(NAV_KEY);
        Integer registrations = jdbc.queryForObject(
                "SELECT count(*) FROM plugin_registration pr JOIN plugin_activation pa"
                        + " ON pr.activation_id = pa.id WHERE pa.plugin_id = 'example.inventory'",
                Integer.class);
        assertThat(registrations).isZero();
        Map<String, Object> entity = jdbc.queryForMap(
                "SELECT status FROM meta_entity WHERE name = 'inventory_item'");
        assertThat(entity.get("status")).isEqualTo("disabled");
        // Issue #22 评论-16：实例状态随生命周期派生（active→stopped→uninstalled）
        String instanceStatus = jdbc.queryForObject(
                "SELECT status FROM plugin_instance WHERE plugin_id = 'example.inventory'",
                String.class);
        assertThat(instanceStatus).isEqualTo("uninstalled");
        Integer audit = jdbc.queryForObject(
                "SELECT count(*) FROM sys_audit_event WHERE action = 'plugin.uninstall'"
                        + " AND object_id = 'example.inventory'", Integer.class);
        assertThat(audit).isEqualTo(1);
    }

    private String importAndActivate() throws Exception {
        String validateBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/validate")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                        packageZip))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(validateBody, "$.valid")).isTrue();

        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                        packageZip))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String versionId = JsonPath.read(importBody, "$.versionId");
        return JsonPath.read(PluginPackageTestSupport.activate(mockMvc, adminBearer, versionId),
                "$.id");
    }

    /** 仓库内插件目录打包为 zip（演示脚本同构：文件级 zip，路径为包内相对路径）。 */
    private static byte[] zipPackage(Path packageDir) throws java.io.IOException {
        List<Path> files;
        try (var walk = Files.walk(packageDir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Path file : files) {
                zos.putNextEntry(new ZipEntry(packageDir.relativize(file).toString()
                        .replace('\\', '/')));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
