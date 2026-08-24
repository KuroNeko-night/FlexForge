package com.flexforge.meta.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;

import java.util.Optional;

/**
 * 元数据持久化端口（domain 端口，infrastructure 提供 JDBC 实现）。
 * 写方法返回受影响行数或落库后镜像；唯一约束冲突以 DuplicateKeyException 上抛，
 * 由应用层转换为 400（docs/09 P04：统一错误装配）。
 */
@PublicApi
public interface MetaRepository {

    /** 新建实体（初始 draft，无字段无视图）；name 唯一冲突上抛。 */
    EntityRecord insertEntity(String id, String name, String displayName);

    Optional<EntityRecord> findEntity(String id);

    Optional<EntityRecord> findEntityByName(String name);

    /**
     * 更新实体行；返回受影响行数（0 = 不存在或守卫失败）。
     * {@code requireDraftStatus} 为 true 时（实体改名等 breaking 路径）仅在实体仍为 draft 时生效，
     * 关闭"读取状态后实体被启用"的 TOCTOU 窗口。
     */
    int updateEntity(String id, String name, String displayName, EntityStatus status,
                     boolean requireDraftStatus);

    /** 分页列实体；statusFilter 非 null 时按状态过滤（排序白名单已由 PageQuery 强制）。 */
    PageResult<EntityRecord> listEntities(PageQuery query, EntityStatus statusFilter);

    /** 装配完整定义（实体 + 字段按 position,name + 视图按 view_type）。 */
    Optional<EntityDefinition> loadDefinition(String entityId);

    /** 新增字段；(entity_id, name) 唯一冲突上抛。 */
    FieldDefinition insertField(FieldDefinition field);

    Optional<FieldDefinition> findField(String fieldId);

    /**
     * 更新字段行；返回受影响行数（0 = 不存在或守卫失败）。
     * {@code requireDraftEntity} 为 true 时（语义变更路径）仅在其所属实体仍为 draft 时生效，
     * 关闭状态读取与写入之间的并发启用窗口。
     */
    int updateField(FieldDefinition field, boolean requireDraftEntity);

    /** 新增视图；(entity_id, view_type) 唯一冲突上抛。 */
    ViewDefinition insertView(ViewDefinition view);

    Optional<ViewDefinition> findView(String viewId);

    /** 更新视图行；返回受影响行数（0 = 不存在）。 */
    int updateView(ViewDefinition view);
}
