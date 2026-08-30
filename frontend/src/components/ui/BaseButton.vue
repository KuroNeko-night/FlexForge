<script setup lang="ts">
/**
 * 基建按钮（docs/09 P12.5）：变体 primary/secondary/danger/ghost + 尺寸 sm；
 * 样式只消费 --ff-* 令牌，主题插件经变量覆盖改变观感（结构与皮分离）。
 */
withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'danger' | 'ghost';
    size?: 'md' | 'sm';
    type?: 'button' | 'submit';
    disabled?: boolean;
  }>(),
  { variant: 'secondary', size: 'md', type: 'button', disabled: false },
);
</script>

<template>
  <button
    :type="type"
    :disabled="disabled"
    class="ff-btn"
    :class="[`ff-btn--${variant}`, size === 'sm' ? 'ff-btn--sm' : '']"
  >
    <slot />
  </button>
</template>
