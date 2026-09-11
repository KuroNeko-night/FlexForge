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

import static com.flexforge.app.web.PluginPackageTestSupport.menuKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P17 看板视图验收（docs/09 P17）：example.kanban 声明 kanban 视图（groupBy
 * enum 字段）经同一注册链路落 meta_view（group_by 列），实体详情契约下发
 * viewType/groupBy；非法 groupBy 声明激活失败于 REGISTER（FR-PLUGIN 边界）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ExamplePluginsP17Test {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    /** 类级单例包（zip 时间戳字节漂移会使 contentHash 变化，须只构建一次）。 */
    private static byte[] kanbanZip;
    private static Path kanbanDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminBearer;
    private String userBearer;

    @BeforeAll
    static void buildPackages() throws Exception {
        kanbanDir = Path.of("..", "..", "plugins", "example-kanban");
        kanbanZip = zipPackage(kanbanDir, null, null, null);
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
    void kanbanViewRegistersAndServesGroupByContract() throws Exception {
        String activationId = importAndActivate(kanbanZip);
        assertThat(activationId).isNotBlank();
        assertThat(menuKeys(mockMvc, userBearer)).contains("example.kanban.tasks");

        // 台账表迁移落地（docs/07 迁移对象前缀契约）
        Integer table = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables"
                        + " WHERE table_name = 'example_pipeline_task'", Integer.class);
        assertThat(table).isEqualTo(1);

        // 实体详情契约：kanban 视图下发 viewType + groupBy（前端 KanbanView 消费面）
        String detail = mockMvc.perform(
                        MockMvcRequestBuilders.get(
                                        "/api/v1/meta/entities/by-name/pipeline_task")
                                .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> groupBy = JsonPath.read(detail,
                "$.views[?(@.viewType == 'kanban')].groupBy");
        assertThat(groupBy).containsExactly("stage");

        // 业务记录经 data_record：stage 往返（前端按此分组渲染看板列）；
        // date 字段按平台既有契约显式传值（缺失/null 会触发类型校验 400）
        String created = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/data/pipeline_task")
                                .header("Authorization", userBearer)
                                .contentType("application/json")
                                .content("{\"title\":\"接线看板文档\",\"stage\":\"进行中\","
                                        + "\"priority\":\"高\",\"owner\":\"小林\","
                                        + "\"due_on\":\"2026-09-15\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/pipeline_task/" + id)
                        .header("Authorization", userBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stage").value("进行中"));
    }

    @Test
    void kanbanGroupByMustBeDeclaredEnumField() throws Exception {
        // 审查 P1-1：与本类另一用例共享库，若原包已 ACTIVE 会先命中占用拦截——
        // 先停用占用（若在），保证走到 groupBy 校验路径
        stopActiveActivationOf("example.kanban");
        // groupBy 指向 text 字段：激活失败于 REGISTER（声明式边界，P17 红线）；
        // 坏包各用独立版本号（同版本异内容会被字节不可变契约先行拒绝）
        byte[] textGroupZip = zipPackage(kanbanDir, "\"groupBy\": \"stage\"",
                "\"groupBy\": \"title\"", "0.1.1");
        importAndExpectActivationFailed(textGroupZip, "enum 类型");
        // groupBy 缺失：同样拒绝
        byte[] missingGroupZip = zipPackage(kanbanDir, ",\n  \"groupBy\": \"stage\"", "",
                "0.1.2");
        importAndExpectActivationFailed(missingGroupZip, "groupBy");
    }

    /** 非法 groupBy 声明在激活端点即被拒（400）；断言错误消息含 groupBy 语义，
     * 与"插件已有激活占用"等其它 400 路径区分（审查 P1-1：防测试空转）。 */
    private void stopActiveActivationOf(String pluginId) throws Exception {
        String inventory = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/plugins/inventory")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(inventory,
                "$[?(@.pluginId == '" + pluginId + "')].activations[?(@.status == 'ACTIVE')].id");
        for (String id : ids) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + id + "/stop")
                            .header("Authorization", adminBearer))
                    .andExpect(status().isOk());
        }
    }

    private void importAndExpectActivationFailed(byte[] zip, String expectedFragment)
            throws Exception {
        String versionId = importVersion(zip);
        mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        expectedFragment)));
    }

    private String importVersion(byte[] zip) throws Exception {
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(importBody, "$.versionId");
    }

    private String importAndActivate(byte[] zip) throws Exception {
        String versionId = importVersion(zip);
        String activation = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId + "/activate")
                                .header("Authorization", adminBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(activation, "$.id");
    }

    /** 打包仓库插件目录；specTarget 非空时对 kanban 视图规格做字符串替换、
     * version 用于坏包用例 bump 版本（不改动仓库文件）。 */
    private static byte[] zipPackage(Path packageDir, String specTarget, String specReplacement,
                                     String version) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(packageDir)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        var out = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(out)) {
            for (Path file : files) {
                String entry = packageDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                byte[] bytes = Files.readAllBytes(file);
                if (specTarget != null && entry.endsWith("pipeline-task.kanban.json")) {
                    String spec = new String(bytes, StandardCharsets.UTF_8)
                            .replace(specTarget, specReplacement);
                    bytes = spec.getBytes(StandardCharsets.UTF_8);
                }
                if (version != null && entry.endsWith("plugin.json")) {
                    String manifest = new String(bytes, StandardCharsets.UTF_8)
                            .replace("\"version\": \"0.1.3\"", "\"version\": \"" + version + "\"");
                    bytes = manifest.getBytes(StandardCharsets.UTF_8);
                }
                zos.write(bytes);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
