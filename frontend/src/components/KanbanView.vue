<script setup lang="ts">
import { computed } from 'vue';

import type { EntityDetail, FieldDefinition, RecordView, ViewDefinition } from '@/api/types';
import { resolveRenderer } from '@/registry/rendererRegistry';

/**
 * 看板视图（P17，viewType=kanban 平台内置渲染器）：groupBy enum 字段选项为列
 * （声明式列来源，无脚本语义，S5），列头计数；卡片按视图 columns 渲染字段值
 * （复用 display renderer 单点），点击进详情——第一版只读，移动记录=详情内
 * 编辑分组字段。数据为当前加载页（父级一次拉 100 条），计数即已加载计数。
 */
const props = defineProps<{
  definition: EntityDetail;
  view: ViewDefinition;
  records: RecordView[];
}>();
const emit = defineEmits<{ 'card-click': [record: RecordView] }>();

/** groupBy 字段定义（后端保证存在且为 enum）；缺失时安全退化为单列。 */
const groupField = computed<FieldDefinition | null>(
  () => props.definition.fields.find((field) => field.name === props.view.groupBy) ?? null,
);

const options = computed<string[]>(() => {
  const raw = (groupField.value?.validation ?? {}).options;
  return Array.isArray(raw) ? raw.filter((o): o is string => typeof o === 'string') : [];
});

interface KanbanColumn {
  key: string;
  label: string;
  records: RecordView[];
}

const UNSET = '__unset__';

/** 列=枚举选项（声明序）+ 兜底"未设置"列（值不在选项内或为空）。 */
const columns = computed<KanbanColumn[]>(() => {
  const defined: KanbanColumn[] = options.value.map((option) => ({
    key: option,
    label: option,
    records: [],
  }));
  const byKey = new Map(defined.map((column) => [column.key, column]));
  const unset: KanbanColumn = { key: UNSET, label: '未设置', records: [] };
  for (const record of props.records) {
    const value = record.data[props.view.groupBy ?? ''];
    const column = typeof value === 'string' && value !== '' ? byKey.get(value) : null;
    (column ?? unset).records.push(record);
  }
  return unset.records.length > 0 ? [...defined, unset] : defined;
});

/** 卡片显示字段：视图 columns（visible 序）；未配置时退化为全部字段前三个。 */
const cardFields = computed<FieldDefinition[]>(() => {
  const ordered = [...props.definition.fields].sort((a, b) => a.position - b.position);
  const viewColumns = props.view.columns?.filter((column) => column.visible !== false) ?? [];
  if (viewColumns.length === 0) {
    return ordered.slice(0, 3);
  }
  const byName = new Map(ordered.map((field) => [field.name, field]));
  return viewColumns
    .map((column) => byName.get(column.field))
    .filter((field): field is FieldDefinition => field !== undefined);
});
</script>

<template>
  <div class="kanban" :data-entity="definition.name" data-testid="kanban-view">
    <section v-for="column in columns" :key="column.key" class="kanban-col" :data-col="column.key">
      <header class="kanban-col-head">
        <span class="kanban-col-label">{{ column.label }}</span>
        <span class="kanban-col-count">{{ column.records.length }}</span>
      </header>
      <ul class="kanban-cards">
        <li v-for="record in column.records" :key="record.id">
          <button
            type="button"
            class="kanban-card"
            :data-record="record.id"
            @click="emit('card-click', record)"
          >
            <component
              :is="resolveRenderer(field)"
              v-for="field in cardFields"
              :key="field.id"
              :field="field"
              :model-value="(record.data[field.name] ?? null) as never"
              mode="display"
            />
          </button>
        </li>
        <li v-if="column.records.length === 0" class="kanban-empty">暂无记录</li>
      </ul>
    </section>
    <p v-if="columns.length === 0" class="kanban-empty">分列字段缺少枚举选项</p>
  </div>
</template>

<style scoped>
.kanban {
  display: flex;
  gap: var(--ff-space-3);
  align-items: flex-start;
  overflow-x: auto;
  padding-bottom: var(--ff-space-2);
}
.kanban-col {
  flex: 1 0 15rem;
  max-width: 22rem;
  background: var(--ff-surface-muted);
  border-radius: var(--ff-radius-md);
  padding: var(--ff-space-2);
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.kanban-col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--ff-space-1);
}
.kanban-col-label {
  font-weight: 600;
  font-size: var(--ff-text-md);
}
.kanban-col-count {
  background: var(--ff-surface);
  border-radius: 999px;
  padding: 0 var(--ff-space-2);
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.kanban-cards {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.kanban-card {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
  text-align: left;
  padding: var(--ff-space-2) var(--ff-space-3);
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  box-shadow: var(--ff-shadow-1);
  transition:
    border-color var(--ff-motion-fast) var(--ff-ease),
    box-shadow var(--ff-motion-fast) var(--ff-ease);
}
.kanban-card:hover:not(:disabled) {
  border-color: var(--ff-primary);
  box-shadow: var(--ff-shadow-2);
}
.kanban-card :deep(*:first-child) {
  font-weight: 600;
}
.kanban-empty {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
  padding: var(--ff-space-2);
}
</style>
