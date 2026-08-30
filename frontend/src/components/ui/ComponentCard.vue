<script setup lang="ts">
/**
 * 基建组件卡（docs/09 P12.5）：标题/副标题/内容插槽；hoverable 提供悬浮动效
 * （阴影+上移，令牌驱动）。仅文本插值，无 v-html（S5）。
 */
withDefaults(defineProps<{ title: string; subtitle?: string; hoverable?: boolean }>(), {
  subtitle: undefined,
  hoverable: false,
});
</script>

<template>
  <section class="ff-card" :class="hoverable ? 'ff-card--hoverable' : ''">
    <h3 class="ff-card__title">
      <slot name="title">{{ title }}</slot>
    </h3>
    <p v-if="subtitle" class="ff-card__subtitle">
      <slot name="subtitle">{{ subtitle }}</slot>
    </p>
    <slot />
  </section>
</template>
