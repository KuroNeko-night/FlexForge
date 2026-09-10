<script setup lang="ts">
import { computed, reactive } from 'vue';

import type { EntityDetail, FieldDefinition, ViewDefinition } from '@/api/types';
import { resolveRenderer } from '@/registry/rendererRegistry';

/**
 * 动态表单通用组件（新增/编辑共用）：字段顺序 = position（有 form 视图时按其
 * columns 序）；初值来自记录或字段默认值；提交整体上抛，校验以服务端为权威
 * （错误消息回显在调用方，NFR-UX-01 校验失败反馈）。显式空值不提交该键。
 */
const props = defineProps<{
  definition: EntityDetail;
  view: ViewDefinition | null;
  initial?: Record<string, unknown> | null;
  submitLabel: string;
  /** P24：提供即渲染取消按钮（type=button，仅上抛事件，返回导航由调用方决定）。 */
  cancelLabel?: string;
  submitting: boolean;
}>();

const emit = defineEmits<{
  submit: [values: Record<string, unknown>];
  cancel: [];
}>();

const formFields = computed<FieldDefinition[]>(() => {
  const ordered = [...props.definition.fields].sort((a, b) => a.position - b.position);
  const viewColumns = props.view?.columns ?? null;
  if (!viewColumns || viewColumns.length === 0) {
    return ordered;
  }
  const byName = new Map(ordered.map((field) => [field.name, field]));
  return viewColumns
    .map((column) => byName.get(column.field))
    .filter((field): field is FieldDefinition => field !== undefined);
});

// values 是 setup 时的一次性快照：不随 props.initial 变化重置，
// 跨记录/实体复用必须由调用方以 :key 强制重建组件（见 DynamicEntityView）
const values = reactive<Record<string, unknown>>({ ...initialValue() });

function initialValue(): Record<string, unknown> {
  const result: Record<string, unknown> = {};
  for (const field of props.definition.fields) {
    const fromRecord = props.initial?.[field.name];
    result[field.name] = fromRecord !== undefined ? fromRecord : (field.defaultValue ?? null);
  }
  return result;
}

function fieldRenderer(field: FieldDefinition) {
  return resolveRenderer(field);
}

function submit(): void {
  // null = 显式清除（后端 PATCH null 清键、create 丢弃 null）；仅未定义键不提交
  const payload: Record<string, unknown> = {};
  for (const [name, value] of Object.entries(values)) {
    if (value !== undefined) {
      payload[name] = value;
    }
  }
  emit('submit', payload);
}
</script>

<template>
  <form class="dynamic-form" :data-entity="definition.name" @submit.prevent="submit">
    <!-- P25：字段网格容器（auto-fill 自适应列数，排版均匀分布） -->
    <div class="form-fields">
      <div v-for="field in formFields" :key="field.id" class="form-field" :data-field="field.name">
        <label :for="`field-${field.name}`">
          {{ field.displayName }}<span v-if="field.required" class="required-mark">*</span>
        </label>
        <component
          :is="fieldRenderer(field)"
          v-model="values[field.name]"
          :field="field"
          mode="input"
          :disabled="submitting"
        />
      </div>
    </div>
    <div class="form-actions">
      <button type="submit" :disabled="submitting">{{ submitLabel }}</button>
      <button v-if="cancelLabel" type="button" :disabled="submitting" @click="emit('cancel')">
        {{ cancelLabel }}
      </button>
    </div>
  </form>
</template>
