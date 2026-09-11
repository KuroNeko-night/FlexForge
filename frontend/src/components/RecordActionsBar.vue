<script setup lang="ts">
import type { RecordView } from '@/api/types';
import { t } from '@/registry/localeRegistry';

/**
 * 记录动作栏（P19 从 DynamicEntityView 拆出）：渲染 registry 贡献+内置动作，
 * 执行上抛父级（ActionContext 在页面组装）；无业务分支。
 */
defineProps<{ actions: { key: string; label: string }[]; record: RecordView }>();
const emit = defineEmits<{ run: [actionKey: string, record: RecordView] }>();
</script>

<template>
  <button
    v-for="action in actions"
    :key="action.key"
    type="button"
    :data-action="action.key"
    @click.stop="emit('run', action.key, record)"
  >
    {{ t(`action.${action.key}`, action.label) }}
  </button>
</template>
