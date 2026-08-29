<script setup lang="ts">
import type { FieldDefinition } from '@/api/types';

defineProps<{
  field: FieldDefinition;
  modelValue: string | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

// 空日期归一为 null＝显式清除（docs/03 §8 PATCH null 清键）
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>();
</script>

<template>
  <span v-if="mode === 'display'" class="renderer-date">{{ modelValue ?? '—' }}</span>
  <input
    v-else
    type="date"
    :value="modelValue ?? ''"
    :disabled="disabled"
    :aria-label="field.displayName"
    @input="emit('update:modelValue', ($event.target as HTMLInputElement).value || null)"
  />
</template>
