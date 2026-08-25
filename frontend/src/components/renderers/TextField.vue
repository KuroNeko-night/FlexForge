<script setup lang="ts">
import { computed } from 'vue';

import type { FieldDefinition } from '@/api/types';

const props = defineProps<{
  field: FieldDefinition;
  modelValue: string | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>();

function numberRule(key: string): number | undefined {
  const raw = (props.field.validation ?? {})[key];
  return typeof raw === 'number' ? raw : undefined;
}

const hint = computed(() => {
  const parts: string[] = [];
  if (numberRule('minLength') !== undefined) {
    parts.push(`至少 ${numberRule('minLength')} 字`);
  }
  if (numberRule('maxLength') !== undefined) {
    parts.push(`至多 ${numberRule('maxLength')} 字`);
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
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <small v-if="hint" class="hint">{{ hint }}</small>
  </span>
</template>
