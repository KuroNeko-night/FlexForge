<script setup lang="ts">
import { computed, ref } from 'vue';

import type { EntityDetail, FieldDefinition, RecordView, ViewDefinition } from '@/api/types';
import { resolveRenderer } from '@/registry/rendererRegistry';
import { t } from '@/registry/localeRegistry';

/**
 * 看板视图（P17，viewType=kanban 平台内置渲染器）：groupBy enum 字段选项为列
 * （声明式列来源，无脚本语义，S5），列头计数；卡片按视图 columns 渲染字段值
 * （复用 display renderer 单点），点击进详情。P19 增拖拽换列（原生 HTML5 DnD，
 * 平台能力非插件语义）：drop 上抛 card-move（record+目标枚举值），持久化与
 * 乐观/回滚由父级（动态实体页）编排；"未设置"兜底列不可作落点（目标必须是
 * 声明选项），其卡片可拖出。数据为当前加载页（父级一次拉 100 条）。
 */
const props = defineProps<{
  definition: EntityDetail;
  view: ViewDefinition;
  records: RecordView[];
  /** 查询总条数：超出已加载量时提示（看板单页加载 100 条的可见边界）。 */
  total?: number;
}>();
const emit = defineEmits<{
  'card-click': [record: RecordView];
  'card-move': [record: RecordView, targetOption: string];
}>();

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
  /** 枚举列可作拖拽落点；未设置兜底列（key=UNSET）不可。 */
  droppable: boolean;
}

const UNSET = '__unset__';

/** 列=枚举选项（声明序）+ 兜底"未设置"列（值不在选项内或为空）。 */
const columns = computed<KanbanColumn[]>(() => {
  const defined: KanbanColumn[] = options.value.map((option) => ({
    key: option,
    label: option,
    records: [],
    droppable: true,
  }));
  const byKey = new Map(defined.map((column) => [column.key, column]));
  const unset: KanbanColumn = {
    key: UNSET,
    label: t('kanban.unset', '未设置'),
    records: [],
    droppable: false,
  };
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

/** 拖拽会话状态（P19）：源卡片与当前悬停落点列；均为 null 表示空闲。 */
const draggingRecord = ref<RecordView | null>(null);
const dropTarget = ref<string | null>(null);

function onDragStart(record: RecordView, event: DragEvent): void {
  draggingRecord.value = record;
  // 标准载荷（拖出看板/可访问性语义）；drop 处理直接用本地引用不回读（兼容无 dataTransfer 的测试环境）
  event.dataTransfer?.setData('text/plain', record.id);
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
  }
}

function onDragEnd(): void {
  draggingRecord.value = null;
  dropTarget.value = null;
}

/** 拖拽悬停：仅枚举列且非源记录当前所在列接收 drop（dragover 放行 = drop 可落）。 */
function onDragOver(column: KanbanColumn, event: DragEvent): void {
  if (!column.droppable || !draggingRecord.value) {
    return;
  }
  if (recordColumnKey(draggingRecord.value) === column.key) {
    dropTarget.value = null;
    return;
  }
  event.preventDefault();
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move';
  }
  dropTarget.value = column.key;
}

function onDrop(column: KanbanColumn, event: DragEvent): void {
  const record = draggingRecord.value;
  const target = dropTarget.value;
  onDragEnd();
  if (!record || !column.droppable || target !== column.key) {
    return;
  }
  event.preventDefault();
  emit('card-move', record, column.key);
}

/** 离开列时清除该列高亮（relatedTarget 仍在列内属子元素间移动，忽略；审查 P3-5）。 */
function onDragLeave(column: KanbanColumn, event: DragEvent): void {
  if (!column.droppable || dropTarget.value !== column.key) {
    return;
  }
  const into = event.relatedTarget as Node | null;
  const scope = event.currentTarget;
  if (!into || !(scope instanceof Element) || !scope.contains(into)) {
    dropTarget.value = null;
  }
}

/** 记录当前所在列 key（用于源列过滤；不在选项内视为未设置列）。 */
function recordColumnKey(record: RecordView): string {
  const value = record.data[props.view.groupBy ?? ''];
  return typeof value === 'string' && options.value.includes(value) ? value : UNSET;
}
</script>

<template>
  <div class="kanban" :data-entity="definition.name" data-testid="kanban-view">
    <section
      v-for="column in columns"
      :key="column.key"
      class="kanban-col"
      :class="{
        'is-drop-target': dropTarget === column.key,
        'is-unset-col': !column.droppable,
      }"
      :data-col="column.key"
      @dragover="onDragOver(column, $event)"
      @dragleave="onDragLeave(column, $event)"
      @drop="onDrop(column, $event)"
    >
      <header class="kanban-col-head">
        <span class="kanban-col-label">{{ column.label }}</span>
        <span class="kanban-col-count">{{ column.records.length }}</span>
      </header>
      <ul class="kanban-cards">
        <li v-for="(record, index) in column.records" :key="record.id">
          <button
            type="button"
            class="kanban-card"
            :class="{ 'is-dragging': draggingRecord?.id === record.id }"
            :data-record="record.id"
            :style="{ '--stagger-i': Math.min(index, 9) }"
            draggable="true"
            @dragstart="onDragStart(record, $event)"
            @dragend="onDragEnd"
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
        <li v-if="column.records.length === 0" class="kanban-empty">
          {{ t('kanban.emptyColumn', '暂无记录') }}
        </li>
      </ul>
    </section>
    <p v-if="columns.length === 0" class="kanban-empty">
      {{ t('kanban.noColumns', '分列字段缺少枚举选项') }}
    </p>
  </div>
  <p v-if="total !== undefined && records.length < total" class="kanban-note">
    {{ t('kanban.loadedPrefix', '已加载') }} {{ records.length }} / {{ total }}
    {{ t('kanban.loadedTail', '条 · 列计数基于已加载记录，切回表格可查看全部') }}
  </p>
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
  border: 1px solid transparent;
  transition:
    background var(--ff-motion-fast) var(--ff-ease),
    border-color var(--ff-motion-fast) var(--ff-ease);
}
.kanban-col.is-drop-target {
  border-color: var(--ff-primary);
  background: var(--ff-primary-soft);
}
.kanban-col.is-unset-col {
  cursor: no-drop;
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
  cursor: grab;
  animation: ff-card-in var(--ff-motion-base) var(--ff-ease) both;
  animation-delay: calc(var(--stagger-i, 0) * var(--ff-stagger-step));
  transition:
    border-color var(--ff-motion-fast) var(--ff-ease),
    box-shadow var(--ff-motion-fast) var(--ff-ease),
    opacity var(--ff-motion-fast) var(--ff-ease),
    transform var(--ff-motion-fast) var(--ff-ease);
}
.kanban-card:hover:not(:disabled) {
  border-color: var(--ff-primary);
  box-shadow: var(--ff-shadow-2);
}
.kanban-card.is-dragging {
  opacity: 0.45;
  transform: scale(0.98);
  cursor: grabbing;
}
.kanban-card:active {
  cursor: grabbing;
}
.kanban-card :deep(*:first-child) {
  font-weight: 600;
}
@keyframes ff-card-in {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
}
.kanban-empty {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
  padding: var(--ff-space-2);
}
.kanban-note {
  margin: var(--ff-space-2) 0 0;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
</style>
