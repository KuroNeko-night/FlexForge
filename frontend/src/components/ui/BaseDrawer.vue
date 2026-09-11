<script setup lang="ts">
import { onUnmounted, watch } from 'vue';
import { t } from '@/registry/localeRegistry';

/**
 * 基建抽屉（docs/09 P12.5）：Teleport 到 body + 遮罩/面板分离过渡
 * （面板右滑入场，--ff-motion-slow）；Esc 关闭；open 受控。
 */
const props = defineProps<{ open: boolean; title: string }>();
const emit = defineEmits<{ close: [] }>();

function onOverlayClick(): void {
  emit('close');
}
function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close');
  }
}

watch(
  () => props.open,
  (open) => {
    document.removeEventListener('keydown', onKeydown);
    if (open) {
      document.addEventListener('keydown', onKeydown);
    }
  },
  { immediate: true },
);
onUnmounted(() => document.removeEventListener('keydown', onKeydown));
</script>

<template>
  <Teleport to="body">
    <Transition name="ff-drawer">
      <div
        v-if="open"
        class="ff-drawer__overlay"
        data-testid="ff-drawer-overlay"
        @click.self="onOverlayClick"
      >
        <div class="ff-drawer__panel" role="dialog" aria-modal="true" :aria-label="title">
          <header class="ff-drawer__head">
            <h3>{{ title }}</h3>
            <button type="button" class="ff-btn ff-btn--ghost ff-btn--sm" @click="emit('close')">
              {{ t('common.close', '关闭') }}
            </button>
          </header>
          <slot />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ff-drawer__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ff-drawer__head h3 {
  margin: 0;
}
</style>
