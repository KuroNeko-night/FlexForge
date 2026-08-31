<script setup lang="ts">
import { computed } from 'vue';

import type { FieldDefinition } from '@/api/types';

const props = defineProps<{
  field: FieldDefinition;
  modelValue: string | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

// 空选择（""）归一为 null＝显式清除（docs/03 §8 PATCH null 清键）
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>();

const options = computed<string[]>(() => {
  const raw = (props.field.validation ?? {}).options;
  return Array.isArray(raw) ? raw.filter((item): item is string => typeof item === 'string') : [];
});
</script>

<template>
  <span v-if="mode === 'display'" class="renderer-enum" :data-value="modelValue ?? ''">{{
    modelValue ?? '—'
  }}</span>
  <select
    v-else
    :value="modelValue ?? ''"
    :disabled="disabled"
    :aria-label="field.displayName"
    @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value || null)"
  >
    <option value="">请选择</option>
    <option v-for="option in options" :key="option" :value="option">{{ option }}</option>
  </select>
</template>
