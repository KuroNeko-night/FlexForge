package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 元数据 API 测试支撑（P04）：创建实体/字段/视图与状态迁移助手，返回对象 ID。
 */
public final class MetaTestSupport {

    private MetaTestSupport() {
    }

    /** 创建实体（draft），返回实体 ID。 */
    public static String createEntity(MockMvc mockMvc, String bearer, String name) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/meta/entities")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"displayName\":\"" + name + " 显示名\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** 实体状态迁移，断言期望状态码。 */
    public static void transition(MockMvc mockMvc, String bearer, String entityId,
                                  String status, int expectedStatus) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/entities/" + entityId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    /** 新增字段，返回字段 ID（期望 200）；期望其他状态码时返回响应文本。 */
    public static String addField(MockMvc mockMvc, String bearer, String entityId,
                                  String fieldJson, int expectedStatus) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/meta/entities/" + entityId + "/fields")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fieldJson))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** 编辑字段（PATCH），返回响应文本。 */
    public static String patchField(MockMvc mockMvc, String bearer, String fieldId,
                                    String fieldJson, int expectedStatus) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/meta/fields/" + fieldId)
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fieldJson))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** 读取实体详情，返回响应文本。 */
    public static String getEntity(MockMvc mockMvc, String bearer, String entityId,
                                   int expectedStatus) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/meta/entities/" + entityId)
                        .header("Authorization", bearer))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** RB-E2E 场景 A 物料样板：四字段（text 必填/integer 非负/decimal/enum）+ list/form 视图。 */
    public static void seedMaterialFieldsAndViews(MockMvc mockMvc, String bearer,
                                                  String entityId) throws Exception {
        addField(mockMvc, bearer, entityId, "{\"name\":\"name\",\"displayName\":\"名称\","
                + "\"fieldType\":\"text\",\"required\":true,\"position\":0}", 200);
        addField(mockMvc, bearer, entityId, "{\"name\":\"qty\",\"displayName\":\"数量\","
                + "\"fieldType\":\"integer\",\"validation\":{\"min\":0},\"position\":1}", 200);
        addField(mockMvc, bearer, entityId, "{\"name\":\"unit_price\",\"displayName\":\"单价\","
                + "\"fieldType\":\"decimal\",\"position\":2}", 200);
        addField(mockMvc, bearer, entityId, "{\"name\":\"status\",\"displayName\":\"状态\","
                + "\"fieldType\":\"enum\",\"validation\":{\"options\":[\"in_stock\",\"sold_out\"]},"
                + "\"position\":3}", 200);
        addView(mockMvc, bearer, entityId, "{\"viewType\":\"list\",\"name\":\"物料列表\","
                + "\"columns\":[{\"field\":\"name\"},{\"field\":\"qty\",\"visible\":true}]}");
        addView(mockMvc, bearer, entityId, "{\"viewType\":\"form\",\"name\":\"物料表单\","
                + "\"columns\":[{\"field\":\"name\"},{\"field\":\"status\"}]}");
    }

    private static void addView(MockMvc mockMvc, String bearer, String entityId,
                                String viewJson) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/meta/entities/" + entityId + "/views")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(viewJson))
                .andExpect(status().isOk());
    }
}
