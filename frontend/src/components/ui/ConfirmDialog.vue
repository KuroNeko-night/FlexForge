<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch } from 'vue';

import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 统一确认对话框（P16 交互规范）：替代原生 window.confirm——破坏性操作
 * 与需补充说明的操作在此完成确认；requireReason 时原因必填（旁路迁移等
 * 服务端强制项的录入面）。Esc/遮罩点击=取消；open 受控。
 */
const props = withDefaults(
  defineProps<{
    open: boolean;
    title: string;
    message: string;
    confirmLabel?: string;
    cancelLabel?: string;
    danger?: boolean;
    requireReason?: boolean;
    reasonLabel?: string;
    reasonPlaceholder?: string;
    busy?: boolean;
  }>(),
  {
    confirmLabel: '确认',
    cancelLabel: '取消',
    danger: false,
    requireReason: false,
    reasonLabel: '原因',
    reasonPlaceholder: '',
    busy: false,
  },
);
const emit = defineEmits<{
  confirm: [reason: string];
  cancel: [];
}>();

const reason = ref('');
const reasonInput = ref<HTMLInputElement | null>(null);

watch(
  () => props.open,
  async (open) => {
    document.removeEventListener('keydown', onKeydown);
    if (open) {
      document.addEventListener('keydown', onKeydown);
      reason.value = '';
      // 需要原因时焦点直接落在输入框，确认场景少一次点击
      if (props.requireReason) {
        await nextTick();
        reasonInput.value?.focus();
      }
    }
  },
  { immediate: true },
);
onUnmounted(() => document.removeEventListener('keydown', onKeydown));

function submit(): void {
  if (props.busy || (props.requireReason && reason.value.trim() === '')) {
    return;
  }
  emit('confirm', reason.value.trim());
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('cancel');
  }
}

onUnmounted(() => document.removeEventListener('keydown', onKeydown));
</script>

<template>
  <Teleport to="body">
    <Transition name="ff-fade">
      <div
        v-if="open"
        class="ff-modal__overlay"
        data-testid="ff-confirm-overlay"
        @click.self="emit('cancel')"
      >
        <div class="ff-modal__panel" role="dialog" aria-modal="true" :aria-label="title">
          <h3 class="ff-modal__title">{{ title }}</h3>
          <p class="ff-modal__message">{{ message }}</p>
          <label v-if="requireReason" class="ff-modal__reason">
            {{ reasonLabel }}
            <input
              ref="reasonInput"
              v-model="reason"
              rows="1"
              maxlength="500"
              :placeholder="reasonPlaceholder"
              data-testid="confirm-reason"
              @keydown.enter.prevent="submit"
            />
          </label>
          <div class="ff-modal__actions">
            <BaseButton variant="ghost" :disabled="busy" @click="emit('cancel')">
              {{ cancelLabel }}
            </BaseButton>
            <BaseButton
              :variant="danger ? 'danger' : 'primary'"
              :disabled="busy || (requireReason && reason.trim() === '')"
              data-testid="confirm-submit"
              @click="submit"
            >
              {{ busy ? '处理中…' : confirmLabel }}
            </BaseButton>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ff-modal__overlay {
  position: fixed;
  inset: 0;
  background: rgb(15 23 42 / 45%);
  display: grid;
  place-items: center;
  z-index: 40;
}
.ff-modal__panel {
  width: min(26rem, 92vw);
  background: var(--ff-surface);
  border-radius: var(--ff-radius-lg);
  box-shadow: var(--ff-shadow-2);
  padding: var(--ff-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.ff-modal__title {
  margin: 0;
  font-size: 1rem;
}
.ff-modal__message {
  margin: 0;
  color: var(--ff-text-muted);
  white-space: pre-line;
}
.ff-modal__reason {
  display: block;
  font-weight: 600;
  font-size: var(--ff-text-md);
}
.ff-modal__reason input {
  display: block;
  width: 100%;
  margin-top: var(--ff-space-1);
  padding: var(--ff-space-2);
  box-sizing: border-box;
  font-weight: 400;
}
.ff-modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ff-space-2);
}
</style>
