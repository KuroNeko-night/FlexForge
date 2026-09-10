<script setup lang="ts">
/** 平台管理页占位（数据模型等菜单的目标页；具体管理界面属后续阶段）。 */
defineProps<{ title: string }>();
</script>

<template>
  <section class="placeholder-view">
    <div class="placeholder-box">
      <span class="placeholder-mark" aria-hidden="true"></span>
      <h2>{{ title }}</h2>
      <p>该模块的管理界面将在后续阶段提供；动态实体页面见工作台入口。</p>
    </div>
  </section>
</template>

<style scoped>
/* P25：结构化空态卡（居中为空态语义；卡片化与环形标记提升观感） */
.placeholder-view {
  display: flex;
  justify-content: center;
}
.placeholder-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ff-space-2);
  max-width: 24rem;
  margin-top: 12vh;
  padding: var(--ff-space-4);
  text-align: center;
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
}
.placeholder-mark {
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 999px;
  border: 2px dashed var(--ff-border);
}
.placeholder-box p {
  margin: 0;
  color: var(--ff-text-muted);
}
</style>
