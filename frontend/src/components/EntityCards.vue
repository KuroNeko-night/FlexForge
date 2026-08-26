<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { listEnabledEntities } from '@/api/meta';
import { ApiError } from '@/api/client';
import type { EntitySummary } from '@/api/types';
import StateView from '@/components/StateView.vue';

/** 内置部件 workbench.entities：已启用实体入口卡片（元数据驱动）。 */
const entities = ref<EntitySummary[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied'>('loading');
const error = ref<string | null>(null);

onMounted(async () => {
  try {
    entities.value = await listEnabledEntities();
    state.value = 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value =
      e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : null;
  }
});
</script>

<template>
  <section class="entity-cards-widget" data-widget="workbench.entities">
    <StateView v-if="state !== 'ready'" :state="state" :message="error" />
    <StateView v-else-if="entities.length === 0" :state="'empty'" message="暂无已启用实体">
      <p class="empty-hint">实体由开发者在"数据模型"中定义后启用</p>
    </StateView>
    <ul v-else class="entity-cards">
      <li v-for="entity in entities" :key="entity.id">
        <router-link :to="`/data/${entity.name}`">
          <strong>{{ entity.displayName }}</strong>
          <span class="entity-name">{{ entity.name }}</span>
        </router-link>
      </li>
    </ul>
  </section>
</template>
