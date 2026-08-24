package com.flexforge.data.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.data.application.DynamicRecordService;
import com.flexforge.data.domain.RecordEntry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 动态数据接口（FR-META-04，docs/03 §8）：任意已认证角色可对启用实体 CRUD。
 * 查询参数：page/pageSize/sortBy/direction 之外一律按 "字段.操作符=值" 过滤白名单解析；
 * 记录载荷为 JSON 对象，未知字段/类型与规则不符/必填缺失在边界 400。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/data")
public class DataController {

    private static final Set<String> PAGING_KEYS =
            Set.of("page", "pageSize", "sortBy", "direction");

    public record RecordView(String id, String entity, JsonNode data,
                             Instant createdAt, Instant updatedAt) {
    }

    private final DynamicRecordService records;
    private final AuthService authService;

    public DataController(DynamicRecordService records, AuthService authService) {
        this.records = records;
        this.authService = authService;
    }

    @GetMapping("/{entity}")
    public PageResult<RecordView> query(@PathVariable String entity,
                                        @RequestParam Map<String, String> params) {
        PageResult<RecordEntry> result = records.query(entity, toQuery(params));
        return new PageResult<>(result.items().stream()
                .map(record -> toView(entity, record)).toList(),
                result.total(), result.pageNumber(), result.pageSize());
    }

    @GetMapping("/{entity}/{id}")
    public RecordView detail(@PathVariable String entity, @PathVariable String id) {
        return toView(entity, records.detail(entity, id));
    }

    @PostMapping("/{entity}")
    public RecordView create(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                             @PathVariable String entity,
                             @RequestBody JsonNode payload) {
        return toView(entity, records.create(actor(principal), entity, payload));
    }

    @PatchMapping("/{entity}/{id}")
    public RecordView update(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                             @PathVariable String entity,
                             @PathVariable String id,
                             @RequestBody JsonNode patch) {
        return toView(entity, records.update(actor(principal), entity, id, patch));
    }

    @DeleteMapping("/{entity}/{id}")
    public void delete(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                       @PathVariable String entity,
                       @PathVariable String id) {
        records.delete(actor(principal), entity, id);
    }

    /** 组装查询载荷：分页键之外的参数全部作为过滤条件交白名单解析。 */
    private static DynamicRecordService.DataQuery toQuery(Map<String, String> params) {
        Map<String, String> filters = new HashMap<>(params);
        Integer page = intOf(filters.remove("page"), 1, "page");
        Integer pageSize = intOf(filters.remove("pageSize"), PageQuery.firstPage().pageSize(), "pageSize");
        String sortBy = filters.remove("sortBy");
        String direction = filters.remove("direction");
        PageQuery.SortDirection dir = direction == null ? PageQuery.SortDirection.ASC
                : PageQuery.SortDirection.valueOf(direction);
        return new DynamicRecordService.DataQuery(page, pageSize, sortBy, dir, filters);
    }

    private static Integer intOf(String raw, int fallback, String label) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " 必须是整数: " + raw);
        }
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }

    private static RecordView toView(String entity, RecordEntry record) {
        return new RecordView(record.id(), entity, record.data(),
                record.createdAt(), record.updatedAt());
    }
}
