package com.flexforge.runtime;

import com.flexforge.common.contract.Registration;
import com.flexforge.common.contract.ServiceKey;
import com.flexforge.common.registry.ServiceKeys;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ServiceRegistry 注册-撤销回归（P02 验收：注册后可读取、close 后不可见、重复 close 不报错）。
 */
class ServiceRegistryTest {

    private static final ServiceKey<Object> KEY = ServiceKey.of(ServiceKeys.AUDIT, Object.class);

    private final InMemoryServiceRegistry registry = new InMemoryServiceRegistry();

    @Test
    void registeredServiceIsVisibleByFindAndRequire() {
        Object service = new Object();
        Registration registration = registry.register(KEY, service, "act-001");

        assertThat(registration.isActive()).isTrue();
        assertThat(registration.activationId()).isEqualTo("act-001");
        assertThat(registry.find(KEY)).contains(service);
        assertThat(registry.require(KEY)).isSameAs(service);
    }

    @Test
    void requireMissingServiceFailsWithKeyId() {
        assertThatThrownBy(() -> registry.require(KEY))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(ServiceKeys.AUDIT);
    }

    @Test
    void duplicateRegistrationFails() {
        registry.register(KEY, new Object(), "act-001");

        assertThatThrownBy(() -> registry.register(KEY, new Object(), "act-002"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ServiceKeys.AUDIT);
    }

    @Test
    void closedServiceIsInvisibleAndCanReRegister() {
        Object first = new Object();
        Registration registration = registry.register(KEY, first, "act-001");
        registration.close();

        assertThat(registration.isActive()).isFalse();
        assertThat(registry.find(KEY)).isEmpty();

        Object second = new Object();
        registry.register(KEY, second, "act-002");
        assertThat(registry.require(KEY)).isSameAs(second);
    }

    @Test
    void doubleCloseIsIdempotent() {
        Registration registration = registry.register(KEY, new Object(), "act-001");
        registration.close();

        assertThatCode(registration::close).doesNotThrowAnyException();
        assertThat(registration.isActive()).isFalse();
        assertThat(registry.find(KEY)).isEmpty();
    }

    @Test
    void closeAllRevokesOnlyThatActivation() {
        ServiceKey<Object> otherKey = ServiceKey.of(ServiceKeys.META, Object.class);
        Object mine = new Object();
        Object other = new Object();
        registry.register(KEY, mine, "act-001");
        registry.register(otherKey, other, "act-002");

        var revoked = registry.closeAll("act-001");

        assertThat(revoked).hasSize(1);
        assertThat(revoked.get(0).activationId()).isEqualTo("act-001");
        assertThat(registry.find(KEY)).isEmpty();
        assertThat(registry.require(otherKey)).isSameAs(other);
    }

    @Test
    void unknownServiceKeyIsRejected() {
        ServiceKey<Object> unknown = ServiceKey.of("service.evil", Object.class);

        assertThatThrownBy(() -> registry.register(unknown, new Object(), "act-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service.evil");
    }

    @Test
    void staleCloseDoesNotWipeReRegistration() {
        Object first = new Object();
        Registration firstRegistration = registry.register(KEY, first, "act-001");
        firstRegistration.close();
        Object second = new Object();
        registry.register(KEY, second, "act-002");

        // 旧句柄的重复 close 不得移除新注册（审计 P2-1：dispose 身份条件删除）
        assertThatCode(firstRegistration::close).doesNotThrowAnyException();
        assertThat(registry.require(KEY)).isSameAs(second);
    }

    @Test
    void everyRegisteredServiceKeyRoundTrips() {
        for (String id : ServiceKeys.ALL) {
            ServiceKey<Object> key = ServiceKey.of(id, Object.class);
            Registration registration = registry.register(key, new Object(), "act-loop");
            assertThat(registry.require(key)).isNotNull();
            registration.close();
            assertThat(registry.find(key)).isEmpty();
        }
    }
}
