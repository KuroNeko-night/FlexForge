<script setup lang="ts">
import { computed } from 'vue';

import type { FieldDefinition } from '@/api/types';
import { t } from '@/registry/localeRegistry';

const props = defineProps<{
  field: FieldDefinition;
  modelValue: string | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

// 空输入归一为 null＝显式清除（docs/03 §8 PATCH null 清键），与表单提交口径一致
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>();

function numberRule(key: string): number | undefined {
  const raw = (props.field.validation ?? {})[key];
  return typeof raw === 'number' ? raw : undefined;
}

const hint = computed(() => {
  const parts: string[] = [];
  if (numberRule('minLength') !== undefined) {
    parts.push(
      `${t('field.minLength', '至少')} ${numberRule('minLength')} ${t('field.charUnit', '字')}`,
    );
  }
  if (numberRule('maxLength') !== undefined) {
    parts.push(
      `${t('field.maxLength', '至多')} ${numberRule('maxLength')} ${t('field.charUnit', '字')}`,
    );
  }
  return parts.join('，');
});
</script>

<template>
  <span v-if="mode === 'display'" class="renderer-text">{{ modelValue ?? '—' }}</span>
  <span v-else class="renderer-input">
    <input
      type="text"
      :value="modelValue ?? ''"
      :maxlength="numberRule('maxLength')"
      :disabled="disabled"
      :aria-label="field.displayName"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value || null)"
    />
    <small v-if="hint" class="hint">{{ hint }}</small>
  </span>
</template>
