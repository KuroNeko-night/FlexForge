<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';

import type { WorkshopMessage } from '@/api/issues';
import AppIcon from '@/components/AppIcon.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 工坊消息流呈现（P30，QG-4 行数自 IssueWorkshopChat 拆出）：
 * 气泡消息 + 思考态；新消息自动滚底。
 */
const props = defineProps<{ messages: WorkshopMessage[]; sending: boolean }>();

const chatLog = ref<HTMLElement | null>(null);

async function scrollToBottom(): Promise<void> {
  await nextTick();
  chatLog.value?.scrollTo({ top: chatLog.value.scrollHeight });
}

defineExpose({ scrollToBottom });

watch(
  () => props.messages.length,
  () => {
    void scrollToBottom();
  },
);
</script>

<template>
  <div ref="chatLog" class="chat-log" data-testid="workshop-log">
    <div v-for="message in messages" :key="message.id" class="message" :data-role="message.role">
      <span class="avatar" :class="message.role === 'assistant' ? 'avatar-ai' : 'avatar-user'">
        <AppIcon v-if="message.role === 'assistant'" name="chat" :size="13" />
        <template v-else>{{ t('assistant.me', '我') }}</template>
      </span>
      <div class="bubble">
        <span class="role-label">{{
          message.role === 'assistant' ? 'AI' : t('assistant.me', '我')
        }}</span>
        <p class="bubble-text">{{ message.content }}</p>
      </div>
    </div>
    <div v-if="sending" class="message" data-role="assistant" data-testid="workshop-typing">
      <span class="avatar avatar-ai"><AppIcon name="chat" :size="13" /></span>
      <div class="bubble bubble-typing" :aria-label="t('assistant.thinking', '正在思考')">
        <span class="dot" />
        <span class="dot" />
        <span class="dot" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-log {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
  padding: var(--ff-space-2) 0;
  min-height: 0;
}
.message {
  display: flex;
  gap: var(--ff-space-2);
}
.message[data-role='user'] {
  flex-direction: row-reverse;
}
.avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 12px;
  flex-shrink: 0;
}
.avatar-ai {
  background: var(--ff-primary);
  color: var(--ff-primary-ink);
}
.avatar-user {
  background: var(--ff-surface);
  border: 1px solid var(--ff-border);
}
.bubble {
  max-width: min(72ch, 82%);
  background: var(--ff-surface);
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-md);
  padding: var(--ff-space-2) var(--ff-space-3);
}
.message[data-role='user'] .bubble {
  background: var(--ff-primary);
  color: var(--ff-primary-ink);
}
.role-label {
  display: block;
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
  margin-bottom: var(--ff-space-1);
}
.message[data-role='user'] .role-label {
  color: inherit;
  opacity: 0.85;
}
.bubble-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.bubble-typing {
  display: flex;
  gap: 4px;
  align-items: center;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ff-text-muted);
  animation: ff-ws-typing 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.15s;
}
.dot:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes ff-ws-typing {
  0%,
  60%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  30% {
    opacity: 1;
    transform: translateY(-3px);
  }
}
@media (prefers-reduced-motion: reduce) {
  .dot {
    animation: none;
  }
}
</style>
