<script setup lang="ts">
import { ref } from 'vue';

import type { PluginPreset } from '@/api/plugins';
import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 插件预设条（P21，FR-PLUGIN-12 前端消费面）：保存当前启用集合为命名预设、
 * 一键应用（收敛语义）、删除。动作只上抛；确认对话框与 API 在父视图。
 */
defineProps<{
  presets: PluginPreset[];
  busy: boolean;
}>();
const emit = defineEmits<{
  save: [name: string];
  apply: [preset: PluginPreset];
  remove: [preset: PluginPreset];
}>();

const draftName = ref('');

function submitSave(): void {
  const name = draftName.value.trim();
  if (name === '') {
    return;
  }
  draftName.value = '';
  emit('save', name);
}
</script>

<template>
  <section class="preset-bar" data-testid="plugin-preset-bar">
    <div class="preset-head">
      <h3>插件预设</h3>
      <p class="preset-hint">把当前启用中的插件保存为一组预设，随时一键恢复</p>
    </div>
    <div class="preset-form">
      <input
        v-model="draftName"
        data-testid="preset-name-input"
        maxlength="50"
        placeholder="预设名称"
        :disabled="busy"
        @keydown.enter.prevent="submitSave"
      />
      <BaseButton variant="primary" :disabled="busy || draftName.trim() === ''" @click="submitSave">
        保存当前为预设
      </BaseButton>
    </div>
    <ul v-if="presets.length > 0" class="preset-list" data-testid="preset-list">
      <li v-for="preset in presets" :key="preset.id" class="preset-item">
        <span class="preset-name">{{ preset.name }}</span>
        <span class="preset-meta">{{ preset.entries.length }} 个插件</span>
        <span class="preset-actions">
          <BaseButton size="sm" :disabled="busy" @click="emit('apply', preset)">应用</BaseButton>
          <BaseButton size="sm" variant="danger" :disabled="busy" @click="emit('remove', preset)">
            删除
          </BaseButton>
        </span>
      </li>
    </ul>
    <p v-else class="preset-empty">尚无预设</p>
  </section>
</template>

<style scoped>
.preset-bar {
  padding: var(--ff-space-3);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface);
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.preset-head {
  display: flex;
  align-items: baseline;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
}
.preset-head h3 {
  margin: 0;
  font-size: var(--ff-text-md);
}
.preset-hint {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.preset-form {
  display: flex;
  gap: var(--ff-space-2);
}
.preset-form input {
  flex: 1;
  max-width: 16rem;
  padding: var(--ff-space-1) var(--ff-space-2);
}
.preset-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ff-space-2);
}
.preset-item {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  padding: var(--ff-space-1) var(--ff-space-2);
  border: 1px solid var(--ff-border-soft);
  border-radius: 999px;
  font-size: var(--ff-text-sm);
}
.preset-name {
  font-weight: 600;
}
.preset-meta {
  color: var(--ff-text-muted);
}
.preset-actions {
  display: flex;
  gap: var(--ff-space-1);
}
.preset-empty {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
</style>
