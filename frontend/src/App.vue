<script setup lang="ts">
/**
 * 应用壳：路由出口 + 全局样式。安全（S5）：全应用仅文本插值，
 * 无 v-html/eval/new Function；元数据只驱动组件选择与文本。
 */
</script>

<template>
  <router-view />
</template>

<style>
/* 全局基线样式在 styles/base.css（P16 拆分，避免单文件超限） */
</style>
