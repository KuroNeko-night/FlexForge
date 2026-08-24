package com.flexforge.meta.application;

import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.MetaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetaRegistry 缓存语义（service.meta）：按 id 缓存、写后失效重载、版本单调递增。
 */
class MetaRegistryTest {

    private static EntityDefinition definition(String id) {
        return new EntityDefinition(id, "item_" + id, "物品", EntityStatus.ENABLED, null,
                List.of(), List.of());
    }

    @Test
    void cachesByEntityIdUntilEvicted() {
        MetaRepository repository = mock(MetaRepository.class);
        EntityDefinition definition = definition("e1");
        when(repository.loadDefinition("e1")).thenReturn(Optional.of(definition));
        MetaRegistry registry = new MetaRegistry(repository);

        assertThat(registry.findEntity("e1")).contains(definition);
        assertThat(registry.findEntity("e1")).contains(definition);
        verify(repository, times(1)).loadDefinition("e1");

        registry.evict("e1");
        assertThat(registry.findEntity("e1")).contains(definition);
        verify(repository, times(2)).loadDefinition("e1");
    }

    @Test
    void missingEntityIsNotCachedAndRepeatedLookupsHitStorage() {
        MetaRepository repository = mock(MetaRepository.class);
        when(repository.loadDefinition("missing")).thenReturn(Optional.empty());
        MetaRegistry registry = new MetaRegistry(repository);

        assertThat(registry.findEntity("missing")).isEmpty();
        assertThat(registry.findEntity("missing")).isEmpty();
        verify(repository, times(2)).loadDefinition("missing");
    }

    @Test
    void versionIncrementsOnEveryInvalidationEvenWithoutCachedEntry() {
        MetaRegistry registry = new MetaRegistry(mock(MetaRepository.class));
        long initial = registry.version();

        registry.evict("never-cached");
        assertThat(registry.version()).isEqualTo(initial + 1);

        registry.evictAll();
        assertThat(registry.version()).isEqualTo(initial + 2);
    }
}
