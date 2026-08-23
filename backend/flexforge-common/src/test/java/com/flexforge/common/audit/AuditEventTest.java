package com.flexforge.common.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计事件契约回归（P02 出口：审计端口通过测试）。
 */
class AuditEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void auditEventCarriesRegistryPayloadContract() {
        AuditEvent event = new AuditEvent("audit-001", "user-1", "user.create", "user-2",
                "success", OCCURRED_AT);

        assertThat(event.id()).isEqualTo("audit-001");
        assertThat(event.actor()).isEqualTo("user-1");
        assertThat(event.action()).isEqualTo("user.create");
        assertThat(event.objectId()).isEqualTo("user-2");
        assertThat(event.result()).isEqualTo("success");
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void nullComponentIsRejected() {
        assertThatThrownBy(() -> new AuditEvent(null, "user-1", "user.create", "user-2",
                "success", OCCURRED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }
}
