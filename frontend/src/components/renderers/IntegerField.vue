<script setup lang="ts">
import { computed } from 'vue';

import type { FieldDefinition } from '@/api/types';

const props = defineProps<{
  field: FieldDefinition;
  modelValue: number | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>();

function numberRule(key: string): number | undefined {
  const raw = (props.field.validation ?? {})[key];
  return typeof raw === 'number' ? raw : undefined;
}

const hint = computed(() => {
  const parts: string[] = [];
  if (numberRule('min') !== undefined) {
    parts.push(`≥ ${numberRule('min')}`);
  }
  if (numberRule('max') !== undefined) {
    parts.push(`≤ ${numberRule('max')}`);
  }
  return parts.join('，');
});

function onInput(event: Event): void {
  const raw = (event.target as HTMLInputElement).value;
  if (raw === '') {
    emit('update:modelValue', null);
    return;
  }
  const parsed = Number.parseInt(raw, 10);
  if (Number.isNaN(parsed)) {
    return;
  }
  emit('update:modelValue', parsed);
}
</script>

<template>
  <span v-if="mode === 'display'" class="renderer-number">{{ modelValue ?? '—' }}</span>
  <span v-else class="renderer-input">
    <input
      type="number"
      step="1"
      :value="modelValue ?? ''"
      :min="numberRule('min')"
      :max="numberRule('max')"
      :disabled="disabled"
      :aria-label="field.displayName"
      @input="onInput"
    />
    <small v-if="hint" class="hint">{{ hint }}</small>
  </span>
</template>
