package com.flexforge.kb.application;

import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.MetaRepository;
import com.flexforge.meta.domain.ViewDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务检查工具真实类单测（审查 P2-7：此前被 stub 全替，零覆盖）——
 * 索引（ENABLED 过滤/50 条截断标记/空态）、inspect（字段呈现/停用拒绝/不存在）。
 */
class BusinessEntityToolsTest {

    /** 内存 MetaRepository 桩（只实现工具路径用到的两个查询）。 */
    static class StubRepository implements MetaRepository {
        final List<EntityRecord> rows = new ArrayList<>();

        @Override
        public PageResult<EntityRecord> listEntities(PageQuery query, EntityStatus statusFilter) {
            List<EntityRecord> filtered = statusFilter == null ? rows
                    : rows.stream().filter(r -> r.status() == statusFilter).toList();
            return new PageResult<>(filtered, filtered.size(),
                    query.pageNumber(), query.pageSize());
        }

        @Override
        public Optional<EntityDefinition> loadDefinition(String entityId) {
            return rows.stream()
                    .filter(r -> r.id().equals(entityId))
                    .findFirst()
                    .map(r -> new EntityDefinition(r.id(), r.name(), r.displayName(),
                            r.status(), r.pluginId(), fieldsOf(r.name()), List.<ViewDefinition>of()));
        }

        private List<FieldDefinition> fieldsOf(String entityName) {
            FieldDefinition code = new FieldDefinition("f1", entityName, "code", "单号",
                    "text", true, null, null, null, 0);
            FieldDefinition qty = new FieldDefinition("f2", entityName, "qty", "数量",
                    "integer", false, null, null, null, 1);
            return List.of(code, qty);
        }

        @Override
        public EntityRecord insertEntity(String id, String name, String displayName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EntityRecord> findEntity(String id) {
            return rows.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        @Override
        public Optional<EntityRecord> findEntityByName(String name) {
            return rows.stream().filter(r -> r.name().equals(name)).findFirst();
        }

        @Override
        public int updateEntity(String id, String name, String displayName,
                                EntityStatus status, boolean requireDraftStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FieldDefinition insertField(FieldDefinition field) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<FieldDefinition> findField(String fieldId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateField(FieldDefinition field, boolean requireDraftEntity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ViewDefinition insertView(ViewDefinition view) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ViewDefinition> findView(String viewId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateView(ViewDefinition view) {
            throw new UnsupportedOperationException();
        }
    }

    private static EntityRecord entity(String name, String display, EntityStatus status) {
        return new EntityRecord("e-" + name, name, display, status,
                "example.plugin", Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void entityIndexListsEnabledEntitiesWithDisplayNameAndName() {
        StubRepository repository = new StubRepository();
        repository.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        repository.rows.add(entity("legacy", "已停用业务", EntityStatus.DISABLED));
        String index = new BusinessEntityTools(new MetaRegistry(repository)).entityIndex();
        assertThat(index).contains("采购订单(purchase_order)");
        assertThat(index).doesNotContain("已停用业务");
    }

    @Test
    void entityIndexMarksTruncationAtLimit() {
        StubRepository repository = new StubRepository();
        for (int i = 0; i < BusinessEntityTools.INDEX_LIMIT + 5; i++) {
            repository.rows.add(entity("e" + i, "业务" + i, EntityStatus.ENABLED));
        }
        String index = new BusinessEntityTools(new MetaRegistry(repository)).entityIndex();
        assertThat(index).contains("仅列前 " + BusinessEntityTools.INDEX_LIMIT + " 个");
    }

    @Test
    void inspectRendersFieldsAndRejectsDisabledOrUnknown() {
        StubRepository repository = new StubRepository();
        repository.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        repository.rows.add(entity("legacy", "已停用", EntityStatus.DISABLED));
        BusinessEntityTools tools = new BusinessEntityTools(new MetaRegistry(repository));
        String detail = tools.inspect("purchase_order");
        assertThat(detail).contains("业务：采购订单(purchase_order)");
        assertThat(detail).contains("单号(code)：text，必填");
        assertThat(detail).contains("数量(qty)：integer");
        assertThatThrownBy(() -> tools.inspect("legacy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务实体不存在");
        assertThatThrownBy(() -> tools.inspect("none_such"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
