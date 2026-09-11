<script setup lang="ts">
/**
 * 五状态统一反馈（NFR-UX-01 / RB-UI）：loading/empty/error/denied/stale。
 * 文案集中在此，动态页面据此渲染；error 附带后端可诊断信息（含 requestId）。
 */
import { t } from '@/registry/localeRegistry';

export type ViewState = 'loading' | 'empty' | 'error' | 'denied' | 'stale';

/** 渲染期求值（语言包注册晚于模块加载，且切换需即时生效）。 */
function messageOf(state: ViewState): string {
  const messages: Record<ViewState, string> = {
    loading: t('state.loading', '正在加载…'),
    empty: t('state.empty', '暂无数据'),
    error: t('state.error', '加载失败'),
    denied: t('state.denied', '没有访问权限'),
    stale: t('state.stale', '元数据已更新，正在刷新…'),
  };
  return messages[state];
}

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
    <p class="state-message">{{ message ?? messageOf(state) }}</p>
    <p v-if="detail" class="state-detail">{{ detail }}</p>
    <slot />
  </section>
</template>
