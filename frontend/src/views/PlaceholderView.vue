<script setup lang="ts">
/** 平台管理页占位（数据模型/系统管理菜单的目标页；具体管理界面属后续阶段）。 */
defineProps<{ title: string }>();
</script>

<template>
  <section class="placeholder-view">
    <h2>{{ title }}</h2>
    <p>该模块的管理界面将在后续阶段提供；动态实体页面见工作台入口。</p>
  </section>
</template>
