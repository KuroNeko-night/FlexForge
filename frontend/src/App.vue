<script setup lang="ts">
/** 应用壳：路由出口（全局基线样式在 styles/base.css，P16 拆分；P19 一级路由过渡）。 */
</script>

<template>
  <router-view v-slot="{ Component }">
    <Transition name="page-fade" mode="out-in">
      <component :is="Component" />
    </Transition>
  </router-view>
</template>
