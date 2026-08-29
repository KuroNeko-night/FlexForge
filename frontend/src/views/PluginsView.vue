<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { ApiError } from '@/api/client';
import {
  fetchPluginInventory,
  type PluginActivationEntry,
  type PluginInventoryEntry,
} from '@/api/plugins';
import StateView from '@/components/StateView.vue';

/**
 * 插件管理页（docs/09 P12）：inventory 只读视图——生成/导入版本、激活尝试
 * 与失败诊断（stage + errorCode），供演示排障与"插件页显示生成版本"验收。
 * 写操作（导入/启停/卸载）仍走标准插件 API，本页不复制业务逻辑。
 */
const plugins = ref<PluginInventoryEntry[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    plugins.value = await fetchPluginInventory();
    state.value = plugins.value.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value =
      e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : null;
  }
}

/** 失败诊断文案：失败尝试展示"阶段（错误码）"，其余展示当前阶段。 */
function diagnosisOf(activation: PluginActivationEntry): string {
  if (activation.status === 'FAILED') {
    const code = activation.errorCode ? `（${activation.errorCode}）` : '';
    return `失败于 ${activation.stage ?? '?'}${code}`;
  }
  return activation.stage ?? '—';
}

onMounted(load);
</script>

<template>
  <section class="plugins-view" data-testid="plugins-view">
    <header class="plugins-header">
      <h2>插件管理</h2>
      <button type="button" @click="load">刷新</button>
    </header>
    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">尚无插件导入，可经插件包接口导入后查看</p>
    </StateView>
    <ul v-else class="plugin-list">
      <li v-for="plugin in plugins" :key="plugin.pluginId" class="plugin-card">
        <div class="plugin-head">
          <strong>{{ plugin.name }}</strong>
          <span class="plugin-id">{{ plugin.pluginId }}</span>
          <span class="status-badge" :data-status="plugin.instanceStatus">
            {{ plugin.instanceStatus }}
          </span>
        </div>
        <p class="plugin-versions" data-testid="plugin-versions">
          版本：{{ plugin.versions.map((v) => v.version).join('、') || '—' }}
        </p>
        <table class="activation-table" data-testid="activation-table">
          <caption class="sr-only">
            激活尝试
          </caption>
          <thead>
            <tr>
              <th scope="col">状态</th>
              <th scope="col">阶段 / 诊断</th>
              <th scope="col">操作者</th>
              <th scope="col">开始时间</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="activation in plugin.activations"
              :key="activation.id"
              :data-status="activation.status"
            >
              <td>{{ activation.status }}</td>
              <td data-testid="activation-diagnosis">{{ diagnosisOf(activation) }}</td>
              <td>{{ activation.requestedBy ?? '—' }}</td>
              <td>{{ activation.startedAt ?? '—' }}</td>
            </tr>
            <tr v-if="plugin.activations.length === 0">
              <td colspan="4">无激活尝试</td>
            </tr>
          </tbody>
        </table>
      </li>
    </ul>
  </section>
</template>
