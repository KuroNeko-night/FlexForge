package com.flexforge.app.web;

import com.flexforge.common.ApiConstants;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 测试专用端点（test scope，不进入生产构件）：驱动统一错误响应、requestId
 * 与分页白名单的 API 边界断言（P02 验收）。
 *
 * <p>注意（复审 P3-3）：本类位于组件扫描包内，会注册进**所有** @SpringBootTest 上下文
 * （含 ApplicationSmokeTest 的 RANDOM_PORT 真实端口，/api/v1/test/* 在测试中可达）。
 * 仅限测试断言消费，禁止写入任何 API 文档/契约清单。
 */
@RestController
public class TestOnlyApiController {

    static final Set<String> SORT_FIELDS = Set.of("createdAt", "name");

    @GetMapping(ApiConstants.API_V1 + "/test/items")
    public PageResult<String> items(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int pageSize,
                                    @RequestParam(required = false) String sortBy) {
        PageQuery query = PageQuery.of(page, pageSize, sortBy,
                sortBy == null ? PageQuery.SortDirection.ASC : PageQuery.SortDirection.ASC, SORT_FIELDS);
        return new PageResult<>(java.util.List.of("item-1"), 1, query.pageNumber(), query.pageSize());
    }

    @GetMapping(ApiConstants.API_V1 + "/test/boom-validation")
    public String boomValidation() {
        throw new IllegalArgumentException("pageSize 必须在 1..200");
    }

    @GetMapping(ApiConstants.API_V1 + "/test/boom-missing")
    public String boomMissing() {
        throw new NoSuchElementException("service not registered: service.meta");
    }

    @GetMapping(ApiConstants.API_V1 + "/test/boom-unexpected")
    public String boomUnexpected() {
        throw new IllegalStateException("内部状态损坏细节不应外泄");
    }
}
