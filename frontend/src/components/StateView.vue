<script setup lang="ts">
/**
 * 五状态统一反馈（NFR-UX-01 / RB-UI）：loading/empty/error/denied/stale。
 * 文案集中在此，动态页面据此渲染；error 附带后端可诊断信息（含 requestId）。
 */
export type ViewState = 'loading' | 'empty' | 'error' | 'denied' | 'stale';

const MESSAGES: Record<ViewState, string> = {
  loading: '正在加载…',
  empty: '暂无数据',
  error: '加载失败',
  denied: '没有访问权限',
  stale: '元数据已更新，正在刷新…',
};

withDefaults(
  defineProps<{
    state: ViewState;
    message?: string | null;
    detail?: string | null;
  }>(),
  { message: null, detail: null },
);
</script>

<template>
  <section class="state-view" :data-state="state" role="status">
    <p class="state-message">{{ message ?? MESSAGES[state] }}</p>
    <p v-if="detail" class="state-detail">{{ detail }}</p>
    <slot />
  </section>
</template>
