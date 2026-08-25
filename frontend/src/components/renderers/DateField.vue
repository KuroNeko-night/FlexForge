<script setup lang="ts">
import type { FieldDefinition } from '@/api/types';

defineProps<{
  field: FieldDefinition;
  modelValue: string | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

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
