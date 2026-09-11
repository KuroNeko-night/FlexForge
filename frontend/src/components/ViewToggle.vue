<script setup lang="ts">
import { t } from '@/registry/localeRegistry';
/**
 * 列表呈现切换（P17）：表格/看板分段按钮；仅在实体声明 kanban 视图时由
 * 父级渲染。样式在 base.css（.view-toggle）。
 */
defineProps<{ presentation: 'table' | 'kanban' }>();
const emit = defineEmits<{ change: [next: 'table' | 'kanban'] }>();
</script>

<template>
  <div
    class="view-toggle"
    data-testid="view-toggle"
    role="group"
    :aria-label="t('entity.viewToggle', '呈现方式')"
  >
    <button
      type="button"
      :class="{ active: presentation === 'table' }"
      @click="emit('change', 'table')"
    >
      {{ t('entity.table', '表格') }}
    </button>
    <button
      type="button"
      :class="{ active: presentation === 'kanban' }"
      data-testid="kanban-toggle"
      @click="emit('change', 'kanban')"
    >
      {{ t('entity.kanban', '看板') }}
    </button>
  </div>
</template>
