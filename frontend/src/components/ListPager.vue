<script setup lang="ts">
import { computed } from 'vue';

/**
 * 列表分页条（P19 从 DynamicEntityView 拆出：vue 文件行数上限治理，逻辑不变；
 * 样式沿用全局 .pager，不携带业务语义）。
 */
const props = defineProps<{ page: number; total: number; pageSize: number }>();
const emit = defineEmits<{ prev: []; next: [] }>();

const lastPage = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)));
</script>

<template>
  <footer class="pager">
    <button type="button" :disabled="page <= 1" @click="emit('prev')">上一页</button>
    <span>第 {{ page }} / {{ lastPage }} 页 · 共 {{ total }} 条</span>
    <button type="button" :disabled="page >= lastPage" @click="emit('next')">下一页</button>
  </footer>
</template>
