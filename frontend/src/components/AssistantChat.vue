<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';

import type { KbMessage } from '@/api/kb';
import AppIcon from '@/components/AppIcon.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 助手对话呈现（P28，QG-4 行数从 AssistantView 拆出）：消息流 + 引用条目
 * chips + 思考态；新消息/思考态出现时自动滚到底部（容器自持，父层无滚动逻辑）。
 */
const props = defineProps<{ messages: KbMessage[]; asking: boolean }>();

const chatLog = ref<HTMLElement | null>(null);

async function scrollToBottom(): Promise<void> {
  await nextTick();
  chatLog.value?.scrollTo({ top: chatLog.value.scrollHeight });
}

watch(
  () => [props.messages.length, props.asking],
  () => {
    void scrollToBottom();
  },
);
</script>

<template>
  <div ref="chatLog" class="chat-log" data-testid="assistant-log">
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
        <p v-if="message.references.length > 0" class="refs-label">
          {{ t('assistant.references', '引用条目') }}
        </p>
        <ul v-if="message.references.length > 0" class="refs" data-testid="assistant-refs">
          <li v-for="reference in message.references" :key="reference.id">
            {{ reference.title
            }}<span v-if="reference.category" class="ref-category">{{ reference.category }}</span>
          </li>
        </ul>
      </div>
    </div>
    <div v-if="asking" class="message" data-role="assistant" data-testid="assistant-typing">
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
.bubble-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.refs-label {
  margin: var(--ff-space-2) 0 var(--ff-space-1);
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
}
.refs {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ff-space-1);
  margin: 0;
  padding: 0;
}
.refs li {
  font-size: var(--ff-text-xs, 0.75rem);
  background: var(--ff-surface-muted);
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  padding: 2px var(--ff-space-2);
  display: inline-flex;
  gap: var(--ff-space-1);
  align-items: center;
}
.ref-category {
  color: var(--ff-text-muted);
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
  animation: ff-typing 1.2s infinite ease-in-out;
}
.dot:nth-child(2) {
  animation-delay: 0.15s;
}
.dot:nth-child(3) {
  animation-delay: 0.3s;
}
@keyframes ff-typing {
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
