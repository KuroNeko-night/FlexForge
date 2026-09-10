<script setup lang="ts">
/** 平台管理页占位（数据模型等菜单的目标页；具体管理界面属后续阶段）。 */
defineProps<{ title: string }>();
</script>

<template>
  <section class="placeholder-view">
    <div class="placeholder-box">
      <h2>{{ title }}</h2>
      <p>该模块的管理界面将在后续阶段提供；动态实体页面见工作台入口。</p>
    </div>
  </section>
</template>

<style scoped>
/* P24 排版：占位内容居中呈现，不贴左 */
.placeholder-view {
  display: flex;
  justify-content: center;
}
.placeholder-box {
  max-width: 28rem;
  margin-top: 12vh;
  text-align: center;
}
.placeholder-box p {
  color: var(--ff-text-muted);
}
</style>
