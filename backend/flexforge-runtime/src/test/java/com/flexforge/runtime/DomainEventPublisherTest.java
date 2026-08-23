package com.flexforge.runtime;

import com.flexforge.common.contract.DomainEvent;
import com.flexforge.common.contract.Registration;
import com.flexforge.common.registry.DomainEventTypes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 领域事件发布器注册-撤销回归（event.domain，P02 验收）。
 */
class DomainEventPublisherTest {

    private final InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher();

    private static DomainEvent sampleEvent() {
        return new DomainEvent("evt-001", DomainEventTypes.DOMAIN, "agg-001",
                Instant.parse("2026-08-23T10:00:00Z"), Map.of("k", "v"), null);
    }

    @Test
    void subscribedListenerReceivesPublishedEvent() {
        List<DomainEvent> received = new ArrayList<>();
        publisher.subscribe(received::add, "act-001");

        DomainEvent event = sampleEvent();
        publisher.publish(event);

        assertThat(received).containsExactly(event);
    }

    @Test
    void closedListenerStopsReceiving() {
        List<DomainEvent> received = new ArrayList<>();
        Registration registration = publisher.subscribe(received::add, "act-001");
        registration.close();

        publisher.publish(sampleEvent());

        assertThat(registration.isActive()).isFalse();
        assertThat(received).isEmpty();
    }

    @Test
    void doubleCloseIsIdempotent() {
        Registration registration = publisher.subscribe(event -> { }, "act-001");
        registration.close();

        assertThatCode(registration::close).doesNotThrowAnyException();
    }

    @Test
    void closeAllRevokesOnlyThatActivation() {
        List<DomainEvent> receivedA = new ArrayList<>();
        List<DomainEvent> receivedB = new ArrayList<>();
        publisher.subscribe(receivedA::add, "act-001");
        publisher.subscribe(receivedB::add, "act-002");

        var revoked = publisher.closeAll("act-001");
        publisher.publish(sampleEvent());

        assertThat(revoked).hasSize(1);
        assertThat(receivedA).isEmpty();
        assertThat(receivedB).hasSize(1);
    }

    @Test
    void failingListenerPropagatesToPublisher() {
        List<DomainEvent> received = new ArrayList<>();
        publisher.subscribe(event -> {
            throw new IllegalStateException("listener boom");
        }, "act-001");
        publisher.subscribe(received::add, "act-002");

        assertThatThrownBy(() -> publisher.publish(sampleEvent()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listener boom");
    }

    @Test
    void nullEventIsRejected() {
        assertThatThrownBy(() -> publisher.publish(null))
                .isInstanceOf(NullPointerException.class);
    }
}
