<script setup lang="ts">
import { computed } from 'vue';

import type { EntityDetail, FieldDefinition, RecordView, ViewDefinition } from '@/api/types';
import { resolveRenderer } from '@/registry/rendererRegistry';

/**
 * 动态列表通用组件（docs/09 P06 验收 1/2）：列来自 list 视图 columns（缺省=全部字段），
 * 单元格经 renderer registry 按 rendererId 解析内置组件；不含任何业务字段分支。
 * 元数据只用于文本插值与组件选择，不作 HTML/脚本执行（S5）。
 */
const props = defineProps<{
  definition: EntityDetail;
  view: ViewDefinition | null;
  records: RecordView[];
}>();

defineEmits<{ 'row-click': [record: RecordView] }>();

const columns = computed<FieldDefinition[]>(() => {
  const ordered = [...props.definition.fields].sort((a, b) => a.position - b.position);
  const viewColumns = props.view?.columns?.filter((column) => column.visible !== false) ?? null;
  if (!viewColumns || viewColumns.length === 0) {
    return ordered;
  }
  const byName = new Map(ordered.map((field) => [field.name, field]));
  return viewColumns
    .map((column) => byName.get(column.field))
    .filter((field): field is FieldDefinition => field !== undefined);
});

function cellRenderer(field: FieldDefinition) {
  return resolveRenderer(field);
}
</script>

<template>
  <table class="dynamic-table" :data-entity="definition.name">
    <thead>
      <tr>
        <th v-for="column in columns" :key="column.id" scope="col">{{ column.displayName }}</th>
        <th scope="col">操作</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="record in records" :key="record.id" @click="$emit('row-click', record)">
        <td v-for="column in columns" :key="column.id" :data-field="column.name">
          <component
            :is="cellRenderer(column)"
            :field="column"
            :model-value="(record.data[column.name] ?? null) as never"
            mode="display"
          />
        </td>
        <td class="row-actions"><slot name="actions" :record="record" /></td>
      </tr>
    </tbody>
  </table>
</template>
