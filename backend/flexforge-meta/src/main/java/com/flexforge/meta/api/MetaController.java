package com.flexforge.meta.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.meta.application.EntityAdminService;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.ViewDefinition;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 元数据配置接口（FR-META-01..05）：写操作仅 DEVELOPER；读操作全员登录，
 * USER 仅见 enabled 实体（非 enabled 一律 404，不泄露存在性）。非法字段名/类型/
 * renderer ID/校验规则在 API 边界以 400 validation_error 拒绝（统一错误装配）。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/meta")
public class MetaController {

    /** 列表排序白名单（PageQuery 强制，防动态 SQL 注入面）。 */
    private static final Set<String> SORT_FIELDS = Set.of("name", "displayName", "status", "updatedAt");

    public record CreateEntityRequest(String name, String displayName) {
    }

    public record UpdateEntityRequest(String name, String displayName, String status) {
    }

    /** 字段写入载荷（组件数对齐 meta_field 写入列，表行镜像口径）。 */
    public record FieldRequest(String name, String displayName, String fieldType, Boolean required,
                               JsonNode defaultValue, JsonNode validation, String rendererId,
                               Integer position) {
    }

    public record ViewRequest(String viewType, String name, JsonNode columns, JsonNode filters,
                              String groupBy) {
    }

    public record EntityView(String id, String name, String displayName, String status,
                             String pluginId, Instant updatedAt) {
    }

    /** 实体详情（含字段与视图）；metaVersion 供前端比对元数据变更并重新拉取。 */
    public record EntityDetail(String id, String name, String displayName, String status,
                               String pluginId, List<FieldDefinition> fields,
                               List<ViewDefinition> views, long metaVersion) {
    }

    private final EntityAdminService admin;
    private final MetaRegistry registry;
    private final AuthService authService;

    public MetaController(EntityAdminService admin, MetaRegistry registry, AuthService authService) {
        this.admin = admin;
        this.registry = registry;
        this.authService = authService;
    }

    @GetMapping("/entities")
    public PageResult<EntityView> listEntities(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) PageQuery.SortDirection sortDirection) {
        PageQuery query = PageQuery.of(page, pageSize, sortBy,
                sortDirection == null ? PageQuery.SortDirection.ASC : sortDirection, SORT_FIELDS);
        EntityStatus filter = canSeeAllStatuses(principal) ? null : EntityStatus.ENABLED;
        PageResult<EntityRecord> result = registry.listEntities(query, filter);
        List<EntityView> views = result.items().stream().map(MetaController::toView).toList();
        return new PageResult<>(views, result.total(), result.pageNumber(), result.pageSize());
    }

    @GetMapping("/entities/{id}")
    public EntityDetail getEntity(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                  @PathVariable String id) {
        EntityDefinition definition = registry.findEntity(id)
                .orElseThrow(() -> new NoSuchElementException("实体不存在: " + id));
        if (!canSeeAllStatuses(principal) && definition.status() != EntityStatus.ENABLED) {
            throw new NoSuchElementException("实体不存在: " + id);
        }
        return toDetail(definition);
    }

    /** 按名称取实体定义（动态数据页面的元数据入口，URL 用实体名；可见性与 by-id 同口径）。 */
    @GetMapping("/entities/by-name/{name}")
    public EntityDetail getEntityByName(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String name) {
        EntityDefinition definition = registry.findEntityByName(name)
                .orElseThrow(() -> new NoSuchElementException("实体不存在: " + name));
        if (!canSeeAllStatuses(principal) && definition.status() != EntityStatus.ENABLED) {
            throw new NoSuchElementException("实体不存在: " + name);
        }
        return toDetail(definition);
    }

    @PostMapping("/entities")
    @RequireRole(Roles.DEVELOPER)
    public EntityDetail createEntity(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                     @RequestBody CreateEntityRequest request) {
        return toDetail(admin.createEntity(actor(principal), request.name(), request.displayName()));
    }

    @PatchMapping("/entities/{id}")
    @RequireRole(Roles.DEVELOPER)
    public EntityDetail updateEntity(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                     @PathVariable String id,
                                     @RequestBody UpdateEntityRequest request) {
        EntityAdminService.UpdateEntityCommand cmd = new EntityAdminService.UpdateEntityCommand(
                request.name(), request.displayName(), request.status());
        return toDetail(admin.updateEntity(actor(principal), id, cmd));
    }

    @PostMapping("/entities/{id}/fields")
    @RequireRole(Roles.DEVELOPER)
    public FieldDefinition addField(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                    @PathVariable String id,
                                    @RequestBody FieldRequest request) {
        return admin.addField(actor(principal), id, toCommand(request));
    }

    @PatchMapping("/fields/{id}")
    @RequireRole(Roles.DEVELOPER)
    public FieldDefinition updateField(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                       @PathVariable String id,
                                       @RequestBody FieldRequest request) {
        return admin.updateField(actor(principal), id, toCommand(request));
    }

    @PostMapping("/entities/{id}/views")
    @RequireRole(Roles.DEVELOPER)
    public ViewDefinition addView(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                  @PathVariable String id,
                                  @RequestBody ViewRequest request) {
        return admin.addView(actor(principal), id, toCommand(request));
    }

    @PatchMapping("/views/{id}")
    @RequireRole(Roles.DEVELOPER)
    public ViewDefinition updateView(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                     @PathVariable String id,
                                     @RequestBody ViewRequest request) {
        return admin.updateView(actor(principal), id, toCommand(request));
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }

    private static boolean canSeeAllStatuses(AuthPrincipal principal) {
        return principal.hasRole(Roles.DEVELOPER) || principal.hasRole(Roles.ADMIN);
    }

    private static EntityAdminService.FieldCommand toCommand(FieldRequest request) {
        return new EntityAdminService.FieldCommand(request.name(), request.displayName(),
                request.fieldType(), request.required(), normalize(request.defaultValue()),
                normalize(request.validation()), request.rendererId(), request.position());
    }

    private static EntityAdminService.ViewCommand toCommand(ViewRequest request) {
        return new EntityAdminService.ViewCommand(request.viewType(), request.name(),
                normalize(request.columns()), normalize(request.filters()), request.groupBy());
    }

    /** 显式 JSON null（绑定为 NullNode）归一化为"未提供"，与缺省键同语义（PATCH null=不变）。 */
    private static JsonNode normalize(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private EntityDetail toDetail(EntityDefinition definition) {
        return new EntityDetail(definition.id(), definition.name(), definition.displayName(),
                definition.status().wireName(), definition.pluginId(), definition.fields(),
                definition.views(), registry.version());
    }

    private static EntityView toView(EntityRecord record) {
        return new EntityView(record.id(), record.name(), record.displayName(),
                record.status().wireName(), record.pluginId(), record.updatedAt());
    }
}
