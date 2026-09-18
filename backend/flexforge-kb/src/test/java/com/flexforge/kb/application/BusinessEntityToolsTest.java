package com.flexforge.kb.application;

import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.data.application.DynamicRecordService;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.data.domain.RecordFilter;
import com.flexforge.data.domain.RecordRepository;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityRecord;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import com.flexforge.meta.domain.MetaRepository;
import com.flexforge.meta.domain.ViewDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务检查与数据查询工具真实类单测（审查 P2-7：此前被 stub 全替，零覆盖）——
 * 索引（ENABLED 过滤/50 条截断标记/空态）、inspect（字段呈现/停用拒绝/不存在）、
 * query_records（渲染/预算截断/实体口径/过滤透传/参数校验，走真实
 * DynamicRecordService 白名单路径）。
 */
class BusinessEntityToolsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 内存 MetaRepository 桩（只实现工具路径用到的两个查询）。 */
    static class StubRepository implements MetaRepository {
        final List<EntityRecord> rows = new ArrayList<>();
        /** 可选自定义字段集（宽实体预算截断用；缺省 code/qty 两字段）。 */
        List<FieldDefinition> fields;

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
            if (fields != null) {
                return fields;
            }
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

    /** 内存记录仓储桩：捕获过滤/分页参数，返回预置行（query_records 路径专用）。 */
    static class StubRecordRepository implements RecordRepository {
        final List<RecordEntry> rows = new ArrayList<>();
        List<RecordFilter> lastFilters;
        int lastPageSize;

        @Override
        public PageResult<RecordEntry> query(EntityDefinition entity, PageQuery page,
                                             List<RecordFilter> filters) {
            lastFilters = filters;
            lastPageSize = page.pageSize();
            return new PageResult<>(rows, rows.size(), page.pageNumber(), page.pageSize());
        }

        @Override
        public RecordEntry insert(RecordEntry record) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RecordEntry> find(String recordId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateData(String recordId, JsonNode data, Instant expectedUpdatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int delete(String recordId) {
            throw new UnsupportedOperationException();
        }
    }

    /** 组装真实工具链：BusinessEntityTools + DynamicRecordService（同一 MetaRegistry）。 */
    private static BusinessEntityTools tools(StubRepository meta, StubRecordRepository data) {
        MetaRegistry registry = new MetaRegistry(meta);
        DynamicRecordService records = new DynamicRecordService(
                registry, data, event -> { }, Clock.systemUTC());
        return new BusinessEntityTools(registry, records);
    }

    private static RecordEntry record(String id, String entityId, String json) {
        return new RecordEntry(id, entityId, JSON.readTree(json), Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void entityIndexListsEnabledEntitiesWithDisplayNameAndName() {
        StubRepository repository = new StubRepository();
        repository.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        repository.rows.add(entity("legacy", "已停用业务", EntityStatus.DISABLED));
        String index = tools(repository, new StubRecordRepository()).entityIndex();
        assertThat(index).contains("采购订单(purchase_order)");
        assertThat(index).doesNotContain("已停用业务");
    }

    @Test
    void entityIndexMarksTruncationAtLimit() {
        StubRepository repository = new StubRepository();
        for (int i = 0; i < BusinessEntityTools.INDEX_LIMIT + 5; i++) {
            repository.rows.add(entity("e" + i, "业务" + i, EntityStatus.ENABLED));
        }
        String index = tools(repository, new StubRecordRepository()).entityIndex();
        assertThat(index).contains("仅列前 " + BusinessEntityTools.INDEX_LIMIT + " 个");
    }

    @Test
    void inspectRendersFieldsAndRejectsDisabledOrUnknown() {
        StubRepository repository = new StubRepository();
        repository.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        repository.rows.add(entity("legacy", "已停用", EntityStatus.DISABLED));
        BusinessEntityTools businessTools = tools(repository, new StubRecordRepository());
        String detail = businessTools.inspect("purchase_order");
        assertThat(detail).contains("业务：采购订单(purchase_order)");
        assertThat(detail).contains("单号(code)：text，必填");
        assertThat(detail).contains("数量(qty)：integer");
        assertThatThrownBy(() -> businessTools.inspect("legacy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务实体不存在");
        assertThatThrownBy(() -> businessTools.inspect("none_such"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryRecordsRendersRecordsWithDisplayNames() {
        StubRepository meta = new StubRepository();
        meta.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        StubRecordRepository data = new StubRecordRepository();
        data.rows.add(record("rec-1", "e-purchase_order",
                "{\"code\":\"PO-001\",\"qty\":5}"));
        String out = tools(meta, data).queryRecords(
                "purchase_order", "", "", "", 0);
        assertThat(out).contains("业务数据：采购订单(purchase_order) 共 1 条记录");
        assertThat(out).contains("- 编号 rec-1，单号=PO-001，数量=5");
    }

    @Test
    void queryRecordsRejectsUnknownOrDisabledEntityAsIllegalArgument() {
        StubRepository meta = new StubRepository();
        meta.rows.add(entity("legacy", "已停用", EntityStatus.DISABLED));
        BusinessEntityTools businessTools = tools(meta, new StubRecordRepository());
        assertThatThrownBy(() -> businessTools.queryRecords("none_such", "", "", "", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务实体不存在");
        assertThatThrownBy(() -> businessTools.queryRecords("legacy", "", "", "", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务实体不存在");
    }

    @Test
    void queryRecordsClampsLimitAndTruncatesLongValues() {
        StubRepository meta = new StubRepository();
        meta.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        StubRecordRepository data = new StubRecordRepository();
        data.rows.add(record("rec-1", "e-purchase_order",
                "{\"code\":\"" + "长".repeat(100) + "\"}"));
        String out = tools(meta, data).queryRecords(
                "purchase_order", "", "", "", 99);
        assertThat(data.lastPageSize).isEqualTo(BusinessEntityTools.RECORDS_MAX_LIMIT);
        assertThat(out).contains("单号=" + "长".repeat(BusinessEntityTools.RECORDS_VALUE_MAX_CHARS) + "…");
    }

    @Test
    void queryRecordsTruncatesTotalBudgetWithMarker() {
        StubRepository meta = new StubRepository();
        // 宽实体：20 个文本字段 ×60 字符/值 → 单行已远超总预算，第 2 条即截断
        meta.fields = new ArrayList<>();
        for (int f = 0; f < 20; f++) {
            meta.fields.add(new FieldDefinition("f" + f, "purchase_order", "f" + f,
                    "字段" + f, "text", false, null, null, null, f));
        }
        meta.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        StubRecordRepository data = new StubRecordRepository();
        StringBuilder wide = new StringBuilder("{");
        for (int f = 0; f < 20; f++) {
            wide.append(f > 0 ? "," : "").append("\"f").append(f).append("\":\"")
                    .append("值".repeat(BusinessEntityTools.RECORDS_VALUE_MAX_CHARS)).append('"');
        }
        wide.append('}');
        for (int i = 0; i < BusinessEntityTools.RECORDS_MAX_LIMIT; i++) {
            data.rows.add(record("rec-" + i, "e-purchase_order", wide.toString()));
        }
        String out = tools(meta, data).queryRecords("purchase_order", "", "", "", 10);
        assertThat(out).contains("（其余记录已省略）");
        assertThat(out.length()).isLessThanOrEqualTo(
                BusinessEntityTools.RECORDS_TOTAL_MAX_CHARS + "（其余记录已省略）".length() * 2);
    }

    @Test
    void queryRecordsPassesSingleFilterThroughWhitelist() {
        StubRepository meta = new StubRepository();
        meta.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        StubRecordRepository data = new StubRecordRepository();
        tools(meta, data).queryRecords(
                "purchase_order", "code", "contains", "PO-00", 5);
        assertThat(data.lastFilters).hasSize(1);
        RecordFilter filter = data.lastFilters.get(0);
        assertThat(filter.field()).isEqualTo("code");
        assertThat(filter.operator()).isEqualTo("contains");
        assertThat(filter.value()).isEqualTo("PO-00");
    }

    @Test
    void queryRecordsRequiresValueWhenFieldGiven() {
        StubRepository meta = new StubRepository();
        meta.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        assertThatThrownBy(() -> tools(meta, new StubRecordRepository())
                .queryRecords("purchase_order", "code", "", " ", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 value 参数");
    }

    @Test
    void queryRecordsEmptyResultStatesNoRecords() {
        StubRepository meta = new StubRepository();
        meta.rows.add(entity("purchase_order", "采购订单", EntityStatus.ENABLED));
        String out = tools(meta, new StubRecordRepository()).queryRecords(
                "purchase_order", "", "", "", 5);
        assertThat(out).isEqualTo("业务数据：采购订单(purchase_order) 暂无记录。");
    }
}
