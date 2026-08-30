<script setup lang="ts">
import type { PluginActivationEntry, PluginInventoryEntry } from '@/api/plugins';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * 插件卡片（P15 从 PluginsView 拆出）：版本激活/停用/卸载入口 +
 * 激活尝试诊断表（stage+errorCode）。动作只上抛，API 调用与单飞守卫在父视图。
 */
defineProps<{
  plugin: PluginInventoryEntry;
  pending: boolean;
  hasActive: boolean;
}>();
const emit = defineEmits<{
  activate: [versionId: string, version: string];
  stop: [];
  uninstall: [];
}>();

/** 失败诊断文案：失败尝试展示"阶段（错误码）"，其余展示当前阶段。 */
function diagnosisOf(activation: PluginActivationEntry): string {
  if (activation.status === 'FAILED') {
    const code = activation.errorCode ? `（${activation.errorCode}）` : '';
    return `失败于 ${activation.stage ?? '?'}${code}`;
  }
  return activation.stage ?? '—';
}
</script>

<template>
  <ComponentCard :title="plugin.name" :subtitle="plugin.pluginId" hoverable>
    <template #title>
      <div class="plugin-head">
        <strong>{{ plugin.name }}</strong>
        <span class="plugin-id">{{ plugin.pluginId }}</span>
        <span class="status-badge" :data-status="plugin.instanceStatus">
          {{ plugin.instanceStatus }}
        </span>
      </div>
    </template>
    <ul class="version-list" data-testid="plugin-versions">
      <li v-for="v in plugin.versions" :key="v.versionId">
        <code>{{ v.version }}</code>
        <BaseButton size="sm" :disabled="pending" @click="emit('activate', v.versionId, v.version)">
          激活
        </BaseButton>
      </li>
      <li v-if="plugin.versions.length === 0">无版本</li>
    </ul>
    <div class="plugin-ops">
      <BaseButton size="sm" :disabled="pending || !hasActive" @click="emit('stop')">停用</BaseButton>
      <BaseButton size="sm" variant="danger" :disabled="pending" @click="emit('uninstall')">
        卸载
      </BaseButton>
    </div>
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
        <tr v-for="activation in plugin.activations" :key="activation.id" :data-status="activation.status">
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
  </ComponentCard>
</template>

<style scoped>
.plugin-head {
  display: flex;
  align-items: baseline;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
}
.plugin-id {
  color: var(--ff-text-muted);
  font-family: var(--ff-font-mono);
  font-size: var(--ff-text-sm);
}
.status-badge {
  margin-left: auto;
  padding: var(--ff-space-1) var(--ff-space-2);
  border-radius: 999px;
  font-size: var(--ff-text-sm);
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.status-badge[data-status='active'],
.status-badge[data-status='ACTIVE'] {
  background: color-mix(in srgb, var(--ff-primary) 14%, transparent);
  color: var(--ff-primary);
}
.status-badge[data-status='failed'],
.status-badge[data-status='FAILED'] {
  background: var(--ff-danger-bg);
  color: var(--ff-danger);
}
.version-list {
  list-style: none;
  padding: 0;
  margin: var(--ff-space-2) 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
}
.version-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ff-space-2);
  color: var(--ff-text-muted);
  font-size: var(--ff-text-md);
}
.plugin-ops {
  display: flex;
  gap: var(--ff-space-2);
  margin-bottom: var(--ff-space-2);
}
.activation-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--ff-text-sm);
}
.activation-table th,
.activation-table td {
  padding: var(--ff-space-1) var(--ff-space-2);
  border-bottom: 1px solid var(--ff-border-soft);
  text-align: left;
}
.activation-table tr[data-status='FAILED'] td {
  color: var(--ff-danger);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
