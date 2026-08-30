package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RB-PLUGIN-LIFE 共用工具：插件 zip 构建、导入/激活与菜单读取（docs/07 §5）。 */
final class PluginPackageTestSupport {

    private PluginPackageTestSupport() {
    }

    /** 包体内容：迁移声明 JSON / 实体规格 / 迁移 SQL / 依赖声明 JSON。 */
    record PackageBody(String migrations, String entities, String scripts, String dependencies) {
    }

    /** 无依赖、单迁移的默认包体（表名与实体名解耦，便于冲突用例复用）。 */
    static PackageBody defaultBody(String entityName, String tableName) {
        return new PackageBody("[\"migrations/V001__init.sql\"]", entitiesJson(entityName),
                migrationSql(tableName), "[]");
    }

    static byte[] pluginZip(String pluginId, String version, PackageBody body) {
        String manifest = "{\"schemaVersion\":1,\"id\":\"" + pluginId + "\",\"name\":\"" + pluginId
                + "\",\"version\":\"" + version + "\",\"capabilityLevel\":1,"
                + "\"minPlatformVersion\":\"0.1.0\",\"dependencies\":" + body.dependencies() + ","
                + "\"contributions\":{\"navigation\":[\"" + pluginId + ".items\"],"
                + "\"renderers\":[\"enum.default\"]},"
                + "\"resources\":{\"entities\":[\"metadata/entities/item.json\"],"
                + "\"views\":[],\"migrations\":" + body.migrations() + "}}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
        entries.put("metadata/entities/item.json", body.entities().getBytes(StandardCharsets.UTF_8));
        for (String script : body.migrations().replace("[", "").replace("]", "")
                .replace("\"", "").split(",")) {
            String trimmed = script.trim();
            if (!trimmed.isEmpty() && body.scripts() != null) {
                entries.put(trimmed, body.scripts().getBytes(StandardCharsets.UTF_8));
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

    static String entitiesJson(String entityName) {
        return "{\"name\":\"" + entityName + "\",\"displayName\":\"库存项\","
                + "\"fields\":[{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\","
                + "\"required\":true,\"position\":0},{\"name\":\"qty\",\"displayName\":\"数量\","
                + "\"fieldType\":\"integer\",\"validation\":{\"min\":0},\"position\":1}]}";
    }

    static String migrationSql(String tableName) {
        return "CREATE TABLE " + tableName + " (id SERIAL PRIMARY KEY,"
                + " data JSONB NOT NULL DEFAULT '{}');";
    }

    /** 导入指定字节包（幂等重导入路径用：同一数组贯穿全程，不重建 zip 引入哈希漂移）。 */
    static String importVersion(MockMvc mockMvc, String bearer, byte[] zipBytes) throws Exception {
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                        zipBytes))
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(importBody, "$.versionId");
    }

    /** 导入包并返回 versionId。 */
    static String importVersion(MockMvc mockMvc, String bearer, String pluginId, String version,
                                PackageBody body) throws Exception {
        String importBody = mockMvc.perform(
                        MockMvcRequestBuilders.multipart("/api/v1/plugins/import")
                                .file(new MockMultipartFile("file", "pkg.zip", "application/zip",
                                        pluginZip(pluginId, version, body)))
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(importBody, "$.versionId");
    }

    /** 激活并断言 ACTIVE，返回响应体（$.id 为 activationId）。 */
    static String activate(MockMvc mockMvc, String bearer, String versionId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/plugins/" + versionId
                        + "/activate").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
    }

    /** 默认包体导入并激活，返回 activationId。 */
    static String importAndActivate(MockMvc mockMvc, String bearer, String pluginId,
                                    String version) throws Exception {
        String table = pluginId.replace('.', '_') + "_item";
        String versionId = importVersion(mockMvc, bearer, pluginId, version,
                defaultBody(table, table));
        return JsonPath.read(activate(mockMvc, bearer, versionId), "$.id");
    }

    /** 当前用户菜单 key 列表（extension.navigation 消费面验证）。 */
    static List<String> menuKeys(MockMvc mockMvc, String bearer) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/menus")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].key");
    }
}
