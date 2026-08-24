package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P05 性能基线（docs/09 P05 验收：演示数据量下主要 CRUD 接口 P95 < 500ms）。
 * 200 条种子 + 50 轮 list/detail/create 混合采样；MockMvc 计时含过滤器链与
 * JSON 序列化，容器网络开销与真实部署同量级偏保守。CI 波动余量按 500ms 上限。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataPerformanceBaselineTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final int SEED = 200;
    private static final int SAMPLES = 50;
    private static final long P95_LIMIT_MS = 500;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void seed() throws Exception {
        AuthTestSupport.seedUsers(jdbc);
        String developer = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.DEVELOPER_USERNAME, AuthTestSupport.developerPassword());
        String user = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());
        String entityId = MetaTestSupport.createEntity(mockMvc, developer, "data_perf_item");
        MetaTestSupport.addField(mockMvc, developer, entityId,
                "{\"name\":\"sku\",\"displayName\":\"SKU\",\"fieldType\":\"text\",\"required\":true}",
                200);
        MetaTestSupport.addField(mockMvc, developer, entityId,
                "{\"name\":\"qty\",\"displayName\":\"数量\",\"fieldType\":\"integer\","
                        + "\"validation\":{\"min\":0}}", 200);
        MetaTestSupport.transition(mockMvc, developer, entityId, "enabled", 200);

        for (int i = 0; i < SEED; i++) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/data_perf_item")
                            .header("Authorization", user)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sku\":\"SKU-" + i + "\",\"qty\":" + i + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
    }

    @Test
    void mainCrudP95UnderLimit() throws Exception {
        String user = "Bearer " + AuthTestSupport.loginToken(mockMvc,
                AuthTestSupport.USER_USERNAME, AuthTestSupport.userPassword());

        // 预热（类加载/连接池/JIT）
        for (int i = 0; i < 5; i++) {
            list(user);
        }

        List<Long> samples = new ArrayList<>(SAMPLES * 3);
        String firstId = (String) JsonPath.read(list(user), "$.items[0].id");
        for (int i = 0; i < SAMPLES; i++) {
            long start = System.nanoTime();
            list(user);
            samples.add((System.nanoTime() - start) / 1_000_000);

            start = System.nanoTime();
            mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/data_perf_item/" + firstId)
                            .header("Authorization", user))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            samples.add((System.nanoTime() - start) / 1_000_000);

            start = System.nanoTime();
            mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/data/data_perf_item")
                            .header("Authorization", user)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sku\":\"perf-" + i + "\",\"qty\":" + i + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            samples.add((System.nanoTime() - start) / 1_000_000);
        }

        samples.sort(Long::compareTo);
        int p95Index = (int) Math.ceil(samples.size() * 0.95) - 1;
        long p95 = samples.get(p95Index);
        System.out.println("[data-perf] samples=" + samples.size() + " p95=" + p95 + "ms"
                + " max=" + samples.get(samples.size() - 1) + "ms");
        assertThat(p95).as("主要 CRUD 接口 P95 应 < %dms（docs/09 P05）", P95_LIMIT_MS)
                .isLessThan(P95_LIMIT_MS);
    }

    private String list(String bearer) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/data/data_perf_item")
                        .queryParam("pageSize", "20")
                        .queryParam("sortBy", "qty").queryParam("direction", "DESC")
                        .queryParam("qty.gte", "10")
                        .header("Authorization", bearer))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
