package com.flexforge.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P02 验收：任一 API 错误都返回稳定错误码、消息与 requestId；分页白名单在 API 边界拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ApiContractTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String PROPAGATED_REQUEST_ID = "req-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void itemsReturnPageResultEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/test/items").queryParam("page", "2").queryParam("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0]").value("item-1"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pageNumber").value(2))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void pageSizeOverLimitIsRejectedAtApiBoundary() throws Exception {
        mockMvc.perform(get("/api/v1/test/items").queryParam("pageSize", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("req-[0-9a-f]+")));
    }

    @Test
    void sortByOutsideWhitelistIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/test/items").queryParam("sortBy", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void illegalArgumentMapsToValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("pageSize")));
    }

    @Test
    void missingElementMapsToNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.message").value(containsString("service.meta")));
    }

    @Test
    void unexpectedErrorHidesInternalDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test/boom-unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.message").value("服务内部错误"))
                .andExpect(jsonPath("$.message").value(not(containsString("内部状态"))));
    }

    @Test
    void unknownPathMapsToNotFoundNotInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/test/nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("req-[0-9a-f]+")));
    }

    @Test
    void nonNumericPageSizeMapsToValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/test/items").queryParam("pageSize", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void requestIdIsPropagatedFromHeaderToResponseAndBody() throws Exception {
        String body = mockMvc.perform(get("/api/v1/test/boom-validation")
                        .header(RequestIdFilter.REQUEST_ID_HEADER, PROPAGATED_REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, PROPAGATED_REQUEST_ID))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(PROPAGATED_REQUEST_ID);
    }

    @Test
    void malformedRequestIdHeaderIsRegenerated() throws Exception {
        mockMvc.perform(get("/api/v1/test/items").header(RequestIdFilter.REQUEST_ID_HEADER, "req-evil"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, matchesPattern("req-[0-9a-f]{8,64}")));
    }
}
