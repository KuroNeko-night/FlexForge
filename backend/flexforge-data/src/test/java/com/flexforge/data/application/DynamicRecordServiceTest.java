package com.flexforge.data.application;

import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.data.domain.RecordRepository;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 乐观并发守卫两分逻辑失败路径测试（PR #16 复审 P3，AGENTS §4.9）：
 * updateData 0 行时按记录存在性区分"并发修改 400"与"不存在 404"。
 */
class DynamicRecordServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String ENTITY_NAME = "inventory_item";

    private static EntityDefinition enabledEntity() {
        com.flexforge.meta.domain.FieldDefinition sku =
                new com.flexforge.meta.domain.FieldDefinition("f1", "e1", "sku", "SKU",
                        "text", false, null, null, null, 0);
        return new EntityDefinition("e1", ENTITY_NAME, "库存项", EntityStatus.ENABLED, null,
                java.util.List.of(sku), java.util.List.of());
    }

    private static RecordEntry record() {
        return new RecordEntry("rec-1", "e1", JSON.readTree("{\"sku\":\"SKU-1\"}"),
                Instant.parse("2026-08-24T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z"));
    }

    private DynamicRecordService service(MetaRegistry meta, RecordRepository repository) {
        return new DynamicRecordService(meta, repository, mock(AuditEventPort.class),
                Clock.systemUTC());
    }

    @Test
    void zeroRowsWithRecordStillPresentMeansConcurrentModification() {
        MetaRegistry meta = mock(MetaRegistry.class);
        RecordRepository repository = mock(RecordRepository.class);
        when(meta.findEntityByName(ENTITY_NAME)).thenReturn(Optional.of(enabledEntity()));
        when(repository.find("rec-1")).thenReturn(Optional.of(record()));
        when(repository.updateData(eq("rec-1"), any(JsonNode.class), any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service(meta, repository)
                .update("tester", ENTITY_NAME, "rec-1", JSON.readTree("{\"sku\":\"SKU-2\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("并发修改");
    }

    @Test
    void zeroRowsWithRecordGoneMeansNotFound() {
        MetaRegistry meta = mock(MetaRegistry.class);
        RecordRepository repository = mock(RecordRepository.class);
        when(meta.findEntityByName(ENTITY_NAME)).thenReturn(Optional.of(enabledEntity()));
        when(repository.find("rec-1")).thenReturn(Optional.of(record()))
                .thenReturn(Optional.empty());
        when(repository.updateData(eq("rec-1"), any(JsonNode.class), any(Instant.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service(meta, repository)
                .update("tester", ENTITY_NAME, "rec-1", JSON.readTree("{\"sku\":\"SKU-2\"}")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("记录不存在");
    }

    @Test
    void successfulUpdateReReadsRecord() {
        MetaRegistry meta = mock(MetaRegistry.class);
        RecordRepository repository = mock(RecordRepository.class);
        when(meta.findEntityByName(ENTITY_NAME)).thenReturn(Optional.of(enabledEntity()));
        RecordEntry current = record();
        when(repository.find("rec-1")).thenReturn(Optional.of(current));
        when(repository.updateData(eq("rec-1"), any(JsonNode.class), any(Instant.class)))
                .thenReturn(1);

        RecordEntry updated = service(meta, repository)
                .update("tester", ENTITY_NAME, "rec-1", JSON.readTree("{\"sku\":\"SKU-2\"}"));
        assertThat(updated.id()).isEqualTo("rec-1");
    }
}
