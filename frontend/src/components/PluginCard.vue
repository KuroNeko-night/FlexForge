<script setup lang="ts">
import { computed, ref } from 'vue';

import type { PluginInventoryEntry } from '@/api/plugins';
import BaseSwitch from '@/components/ui/BaseSwitch.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * 插件卡片（P21 操作逻辑重构）：只呈现当前版本与启停开关——开=激活当前版本，
 * 关=停用；激活历史表不再展示，最近一次失败保留一行诊断；多版本收纳进展开区
 * （切换经 upgrade 语义，服务端保证占用与补偿）。动作只上抛，API 与单飞守卫在父视图。
 */
const props = defineProps<{
  plugin: PluginInventoryEntry;
  pending: boolean;
}>();
const emit = defineEmits<{
  toggle: [next: boolean];
  switchVersion: [versionId: string, version: string];
  uninstall: [];
}>();

const versionsOpen = ref(false);

const activeEntry = computed(() =>
  props.plugin.activations.find((item) => item.status === 'ACTIVE'),
);
const hasActive = computed(() => activeEntry.value !== undefined);
/** 当前版本：启用中取占用版本，未启用取最新导入版本（清单按创建时间升序）；
 * 占用版本已不在清单（异常数据）时回退最新导入，避免"已启用/无版本"自相矛盾。 */
const currentVersion = computed(() => {
  const occupying = activeEntry.value?.pluginVersionId;
  return (
    props.plugin.versions.find((v) => v.versionId === occupying) ?? props.plugin.versions.at(-1)
  );
});
/** 最近一次失败诊断（清单按开始时间倒序，首个 FAILED 即最近）。
 * 降噪（P24）：已有更新的成功激活时陈旧失败不呈现——只在失败晚于当前
 * ACTIVE（如升级失败经补偿回旧版）或从未成功激活时保留诊断行。 */
const lastFailure = computed(() => {
  const activations = props.plugin.activations;
  const failedIdx = activations.findIndex((item) => item.status === 'FAILED');
  if (failedIdx < 0) {
    return null;
  }
  const activeIdx = activations.findIndex((item) => item.status === 'ACTIVE');
  if (activeIdx >= 0 && activeIdx < failedIdx) {
    return null;
  }
  const failed = activations[failedIdx];
  const code = failed.errorCode ? ` · ${failed.errorCode}` : '';
  return `最近激活失败于 ${failed.stage ?? '?'}${code}`;
});
const extraVersions = computed(() =>
  props.plugin.versions.filter((v) => v.versionId !== currentVersion.value?.versionId),
);
</script>

<template>
  <ComponentCard :title="plugin.name" :subtitle="plugin.pluginId" hoverable>
    <template #title>
      <div class="plugin-head">
        <strong>{{ plugin.name }}</strong>
        <span class="plugin-id">{{ plugin.pluginId }}</span>
      </div>
    </template>
    <div class="plugin-row" data-testid="plugin-toggle-row">
      <span class="version-badge" data-testid="plugin-current-version">
        {{ currentVersion ? `v${currentVersion.version}` : '无版本' }}
      </span>
      <span class="state-label" :data-state="hasActive ? 'on' : 'off'">
        {{ hasActive ? '已启用' : '已停用' }}
      </span>
      <BaseSwitch
        class="plugin-switch"
        :model-value="hasActive"
        :disabled="pending || plugin.versions.length === 0"
        label="启用插件"
        data-testid="plugin-toggle"
        @update:model-value="emit('toggle', $event)"
      />
    </div>
    <p v-if="lastFailure" class="plugin-failure" data-testid="plugin-last-failure">
      {{ lastFailure }}
    </p>
    <div v-if="plugin.versions.length > 1" class="plugin-versions-toggle">
      <button type="button" class="versions-link" @click="versionsOpen = !versionsOpen">
        {{ versionsOpen ? '收起版本' : `另有 ${extraVersions.length} 个版本` }}
      </button>
    </div>
    <ul v-if="versionsOpen" class="version-list" data-testid="plugin-versions">
      <li v-for="v in extraVersions" :key="v.versionId">
        <code>{{ v.version }}</code>
        <BaseButton
          size="sm"
          :disabled="pending"
          @click="emit('switchVersion', v.versionId, v.version)"
        >
          切换到此版本
        </BaseButton>
      </li>
    </ul>
    <div class="plugin-ops">
      <BaseButton size="sm" variant="danger" :disabled="pending" @click="emit('uninstall')">
        卸载
      </BaseButton>
    </div>
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
.plugin-row {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  margin: var(--ff-space-2) 0;
}
.version-badge {
  padding: var(--ff-space-1) var(--ff-space-2);
  border-radius: var(--ff-radius-sm);
  font-family: var(--ff-font-mono);
  font-size: var(--ff-text-sm);
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.state-label {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.state-label[data-state='on'] {
  color: var(--ff-primary);
}
.plugin-switch {
  margin-left: auto;
}
.plugin-failure {
  margin: 0 0 var(--ff-space-2);
  font-size: var(--ff-text-sm);
  color: var(--ff-danger);
}
.plugin-versions-toggle {
  margin-bottom: var(--ff-space-1);
}
.versions-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--ff-primary);
  font-size: var(--ff-text-sm);
  cursor: pointer;
}
.versions-link:hover {
  text-decoration: underline;
}
.version-list {
  list-style: none;
  padding: 0;
  margin: var(--ff-space-1) 0 var(--ff-space-2);
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
  justify-content: flex-end;
}
</style>
