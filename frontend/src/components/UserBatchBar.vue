<script setup lang="ts">
import { ref } from 'vue';

import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';

/**
 * 批量操作条（P24，FR-AUTH-05）：有选择时呈现；批量停用走危险确认（影响多个
 * 账号登录），批量启用直发；确认对话内嵌本组件，动作以 run(status) 上抛，
 * API 与单飞守卫在父层 useUserBatch。
 */
defineProps<{
  count: number;
  running: boolean;
  error: string | null;
  notice: string | null;
}>();

const emit = defineEmits<{
  run: [status: 'ACTIVE' | 'BLOCKED'];
  clear: [];
}>();

const confirming = ref<'BLOCKED' | null>(null);

function request(status: 'ACTIVE' | 'BLOCKED'): void {
  if (status === 'BLOCKED') {
    confirming.value = 'BLOCKED';
    return;
  }
  emit('run', status);
}
</script>

<template>
  <!-- 审查 P3-3：成功/失败反馈独立于选择呈现（成功清选后通知仍可见，
       下一次勾选/清除时由 useUserBatch 清反馈） -->
  <div
    v-if="count > 0 || notice !== null || error !== null"
    class="batch-bar"
    data-testid="user-batch-bar"
  >
    <template v-if="count > 0">
      <span class="batch-count">已选 {{ count }} 项</span>
      <BaseButton
        size="sm"
        :disabled="running"
        data-testid="batch-block"
        @click="request('BLOCKED')"
      >
        批量停用
      </BaseButton>
      <BaseButton
        size="sm"
        :disabled="running"
        data-testid="batch-activate"
        @click="request('ACTIVE')"
      >
        批量启用
      </BaseButton>
      <BaseButton size="sm" variant="ghost" :disabled="running" @click="emit('clear')">
        清除选择
      </BaseButton>
    </template>
    <span v-if="notice" class="batch-notice" role="status">{{ notice }}</span>
    <span v-else-if="error" class="form-error" role="alert">{{ error }}</span>
  </div>
  <ConfirmDialog
    :open="confirming !== null"
    title="批量停用账号"
    :message="`将停用选中的 ${count} 个账号，停用后这些账号无法登录新会话。`"
    confirm-label="停用"
    :danger="true"
    :busy="running"
    @confirm="
      confirming = null;
      emit('run', 'BLOCKED');
    "
    @cancel="confirming = null"
  />
</template>

<style scoped>
.batch-bar {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
  padding: var(--ff-space-2) var(--ff-space-3);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
}
.batch-count {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.batch-notice {
  font-size: var(--ff-text-sm);
  color: var(--ff-primary);
}
</style>
