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
const panel = ref<HTMLDivElement | null>(null);
let restoreFocus: HTMLElement | null = null;

/** 焦点陷阱（审查 P2-8）：Tab/Shift+Tab 在面板可聚焦元素间循环。 */
function trapTab(event: KeyboardEvent): void {
  if (event.key !== 'Tab' || !panel.value) {
    return;
  }
  const focusable = panel.value.querySelectorAll<HTMLElement>(
    'button, input, [tabindex]:not([tabindex="-1"])',
  );
  if (focusable.length === 0) {
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('cancel');
  } else {
    trapTab(event);
  }
}

function releaseFocus(): void {
  restoreFocus?.focus();
  restoreFocus = null;
}

watch(
  () => props.open,
  async (open) => {
    document.removeEventListener('keydown', onKeydown);
    if (open) {
      document.addEventListener('keydown', onKeydown);
      reason.value = '';
      restoreFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      await nextTick();
      // 需要原因时焦点落输入框；否则落取消按钮——Enter 不会误触发破坏性确认
      if (props.requireReason) {
        reasonInput.value?.focus();
      } else {
        panel.value?.querySelectorAll<HTMLButtonElement>('button')[0]?.focus();
      }
    } else {
      releaseFocus();
    }
  },
  { immediate: true },
);
onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown);
  releaseFocus();
});

function submit(): void {
  if (props.busy || (props.requireReason && reason.value.trim() === '')) {
    return;
  }
  emit('confirm', reason.value.trim());
}

/** IME 组态中的回车是选词不是提交（PR #34 审查 P2 同口径，本次审查 P1-2）。 */
function onReasonEnter(event: KeyboardEvent): void {
  if (!event.isComposing) {
    submit();
  }
}
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
        <div
          ref="panel"
          class="ff-modal__panel"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
        >
          <h3 class="ff-modal__title">{{ title }}</h3>
          <p class="ff-modal__message">{{ message }}</p>
          <label v-if="requireReason" class="ff-modal__reason">
            {{ reasonLabel }}
            <input
              ref="reasonInput"
              v-model="reason"
              maxlength="500"
              :placeholder="reasonPlaceholder"
              data-testid="confirm-reason"
              @keydown.enter.prevent="onReasonEnter"
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
  background: rgb(9 9 11 / 45%);
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
