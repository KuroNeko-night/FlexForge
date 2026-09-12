<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { askKb, clearKbMessages, fetchKbMessages, type KbMessage } from '@/api/kb';
import AssistantChat from '@/components/AssistantChat.vue';
import AppIcon from '@/components/AppIcon.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import StateView from '@/components/StateView.vue';
import { t } from '@/registry/localeRegistry';

/**
 * AI 助手页（FR-KB-03）：基于知识库检索的问答客服。会话按用户服务端隔离，
 * 回答与引用条目呈现拆在 AssistantChat；模型不可用错误内联呈现且不破坏输入。
 * 发送交互与 IssueClarifyChat 同口径（Enter 发送/Shift+Enter 换行/IME 不提交）。
 */
const messages = ref<KbMessage[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied'>('loading');
const error = ref<string | null>(null);
const askError = ref<string | null>(null);
const question = ref('');
const asking = ref(false);
const clearing = ref(false);
const confirmClear = ref(false);

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    messages.value = await fetchKbMessages();
    state.value = 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

async function submitQuestion(): Promise<void> {
  const text = question.value.trim();
  if (asking.value || text === '') {
    return;
  }
  asking.value = true;
  askError.value = null;
  messages.value = [
    ...messages.value,
    { id: `local-${messages.value.length}-u`, role: 'user', content: text, references: [] },
  ];
  question.value = '';
  try {
    const outcome = await askKb(text);
    messages.value = [
      ...messages.value,
      {
        id: `local-${messages.value.length}-a`,
        role: 'assistant',
        content: outcome.answer,
        references: outcome.references,
      },
    ];
  } catch (e) {
    // 失败回滚乐观插入，保留输入便于重试（服务端口径：失败不落会话）
    messages.value = messages.value.slice(0, -1);
    question.value = text;
    askError.value = apiErrorMessage(e, t('assistant.askFailed', '回答失败，请稍后重试'));
  } finally {
    asking.value = false;
  }
}

/** Enter 发送；Shift+Enter 换行；IME 组态中的回车是选词不是提交。 */
function onQuestionKeydown(event: KeyboardEvent): void {
  if (!event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void submitQuestion();
  }
}

async function submitClear(): Promise<void> {
  clearing.value = true;
  try {
    await clearKbMessages();
    messages.value = [];
    confirmClear.value = false;
  } catch (e) {
    askError.value = apiErrorMessage(e, t('assistant.clearFailed', '清空失败，请稍后重试'));
  } finally {
    clearing.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="assistant-page" data-testid="assistant-page">
    <header class="page-head">
      <h2 class="ff-page-title">{{ t('assistant.title', 'AI 助手') }}</h2>
      <p class="page-sub">{{ t('assistant.subtitle', '基于企业知识库回答问题') }}</p>
      <BaseButton
        v-if="messages.length > 0"
        variant="ghost"
        data-testid="assistant-clear"
        :disabled="asking || clearing"
        @click="confirmClear = true"
      >
        {{ t('assistant.clear', '清空会话') }}
      </BaseButton>
    </header>
    <StateView v-if="state !== 'ready'" :state="state" :message="error" />
    <template v-else>
      <div v-if="messages.length === 0 && !asking" class="chat-empty">
        <p>
          {{ t('assistant.hint', '你好，我是 FlexForge AI 助手，可以解答企业制度与平台使用问题') }}
        </p>
      </div>
      <AssistantChat v-else :messages="messages" :asking="asking" />
      <p v-if="askError" class="ask-error" role="alert" data-testid="assistant-error">
        {{ askError }}
      </p>
      <div class="composer">
        <textarea
          v-model="question"
          rows="2"
          :placeholder="t('assistant.placeholder', '输入你的问题…')"
          data-testid="assistant-input"
          :disabled="asking"
          @keydown.enter="onQuestionKeydown"
        />
        <BaseButton
          variant="primary"
          :aria-label="t('common.send', '发送')"
          :disabled="asking || question.trim() === ''"
          data-testid="assistant-send"
          @click="submitQuestion"
        >
          <AppIcon name="send" :size="16" />
        </BaseButton>
      </div>
      <p class="composer-hint">
        {{ t('assistant.composerHint', 'Enter 发送，Shift + Enter 换行') }}
      </p>
    </template>
    <ConfirmDialog
      :open="confirmClear"
      :title="t('assistant.clearTitle', '清空会话')"
      :message="t('assistant.clearBody', '将清空当前会话的全部问答记录，不可恢复。')"
      :confirm-label="t('assistant.clear', '清空会话')"
      :danger="true"
      :busy="clearing"
      @confirm="submitClear"
      @cancel="confirmClear = false"
    />
  </section>
</template>

<style scoped>
.assistant-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.page-head {
  display: flex;
  align-items: baseline;
  gap: var(--ff-space-3);
  flex-wrap: wrap;
}
.page-sub {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
  flex: 1;
}
.chat-empty {
  flex: 1;
  display: grid;
  place-content: center;
  color: var(--ff-text-muted);
  text-align: center;
}
.ask-error {
  color: var(--ff-danger);
  font-size: var(--ff-text-sm);
  margin: var(--ff-space-1) 0 0;
}
.composer {
  display: flex;
  gap: var(--ff-space-2);
  align-items: flex-end;
}
.composer textarea {
  flex: 1;
  resize: vertical;
}
.composer-hint {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-xs, 0.75rem);
  margin: var(--ff-space-1) 0 0;
}
</style>
