<script setup lang="ts">
import type { FieldDefinition } from '@/api/types';
import { t } from '@/registry/localeRegistry';

defineProps<{
  field: FieldDefinition;
  modelValue: boolean | null;
  mode: 'display' | 'input';
  disabled?: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
</script>

<template>
  <span v-if="mode === 'display'" class="renderer-boolean" :data-value="modelValue ?? ''">{{
    modelValue === null ? '—' : modelValue ? t('common.yes', '是') : t('common.no', '否')
  }}</span>
  <input
    v-else
    type="checkbox"
    :checked="modelValue === true"
    :disabled="disabled"
    :aria-label="field.displayName"
    @change="emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
  />
</template>
