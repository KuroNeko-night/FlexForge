<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { fetchHealth } from '@/services/health';

type HealthStatus = 'loading' | 'up' | 'down';

const status = ref<HealthStatus>('loading');

const statusText = computed(() => {
  if (status.value === 'up') return '后端服务正常（UP）';
  if (status.value === 'down') return '后端服务不可用（DOWN）';
  return '正在检查后端服务…';
});

onMounted(async () => {
  try {
    const health = await fetchHealth();
    status.value = health.status === 'UP' ? 'up' : 'down';
  } catch {
    status.value = 'down';
  }
});
</script>

<template>
  <main class="shell">
    <h1>FlexForge</h1>
    <p class="intro">模块化数据管理系统（平台骨架，业务以插件交付）</p>
    <section class="health" :data-state="status">
      <span class="dot" aria-hidden="true"></span>
      <span>{{ statusText }}</span>
    </section>
  </main>
</template>

<style scoped>
.shell {
  align-items: center;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  justify-content: center;
  min-height: 100vh;
}

.intro {
  color: #64748b;
  margin: 0;
}

.health {
  align-items: center;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  display: flex;
  gap: 0.5rem;
  padding: 0.5rem 1rem;
}

.dot {
  background: #94a3b8;
  border-radius: 50%;
  height: 0.625rem;
  width: 0.625rem;
}

.health[data-state='up'] .dot {
  background: #16a34a;
}

.health[data-state='down'] .dot {
  background: #dc2626;
}
</style>
