<script setup lang="ts">
import { computed } from 'vue';

import type { EntityDetail, FieldDefinition, RecordView, ViewDefinition } from '@/api/types';
import { t } from '@/registry/localeRegistry';
import { resolveRenderer } from '@/registry/rendererRegistry';
import { visibleColumns } from '@/utils/viewColumns';

/**
 * 动态列表通用组件（docs/09 P06 验收 1/2）：列来自 list 视图 columns（缺省=全部字段），
 * 单元格经 renderer registry 按 rendererId 解析内置组件；不含任何业务字段分支。
 * 元数据只用于文本插值与组件选择，不作 HTML/脚本执行（S5）。P19 列解析收敛到
 * utils/viewColumns（与 CSV 导出同一实现路径，QG-4）。
 */
const props = defineProps<{
  definition: EntityDetail;
  view: ViewDefinition | null;
  records: RecordView[];
}>();

defineEmits<{ 'row-click': [record: RecordView] }>();

const columns = computed<FieldDefinition[]>(() =>
  visibleColumns(props.definition.fields, props.view),
);

function cellRenderer(field: FieldDefinition) {
  return resolveRenderer(field);
}
</script>

<template>
  <table class="dynamic-table" :data-entity="definition.name">
    <thead>
      <tr>
        <th v-for="column in columns" :key="column.id" scope="col">{{ column.displayName }}</th>
        <th scope="col">{{ t('common.actions', '操作') }}</th>
      </tr>
    </thead>
    <tbody>
      <tr
        v-for="(record, index) in records"
        :key="record.id"
        :style="{ '--stagger-i': Math.min(index, 11) }"
        @click="$emit('row-click', record)"
      >
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

<style scoped>
/* P19 行入场 stagger：只动 transform/opacity（不触 layout），时长与步长走令牌
 * （reduced-motion 全归零即静止无延迟）；延迟封顶防长列表等待。 */
tbody tr {
  animation: ff-row-in var(--ff-motion-base) var(--ff-ease) both;
  animation-delay: calc(var(--stagger-i, 0) * var(--ff-stagger-step));
}

@keyframes ff-row-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}
</style>
