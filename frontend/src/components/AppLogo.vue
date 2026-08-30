<script setup lang="ts">
/**
 * 平台品牌标（P15 登录/工作台品牌化）：内联 SVG 的平台基建基线——
 * 登录页无会话不能取签名主题资产（已知限制），品牌识别由平台基线承载；
 * 登录后的整体观感仍由主题插件令牌覆盖（结构与皮分离红线）。
 * 当前色经 currentColor 跟随文字色，主题换色即跟随。
 */
withDefaults(defineProps<{ size?: number }>(), { size: 28 });
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 48 48"
    role="img"
    aria-label="FlexForge"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    <!-- 模块化单体：三块可插拔棱面围绕核心 -->
    <rect x="4" y="6" width="18" height="12" rx="3" fill="currentColor" opacity="0.9" />
    <rect x="26" y="6" width="18" height="12" rx="3" fill="currentColor" opacity="0.45" />
    <rect x="4" y="22" width="18" height="12" rx="3" fill="currentColor" opacity="0.45" />
    <rect x="26" y="22" width="18" height="12" rx="3" fill="currentColor" opacity="0.75" />
    <rect x="14" y="37" width="20" height="6" rx="3" fill="currentColor" opacity="0.9" />
  </svg>
</template>
