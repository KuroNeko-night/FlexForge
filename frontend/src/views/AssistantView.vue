<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { askKb, clearKbMessages, fetchKbMessages, type KbMessage } from '@/api/kb';
import AssistantChat from '@/components/AssistantChat.vue';
import AssistantComposer from '@/components/AssistantComposer.vue';
import IssueChatWorkbench from '@/components/IssueChatWorkbench.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import StateView from '@/components/StateView.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 助手整合页（FR-KB-06，P29）：滑动分段开关切换「AI 助手」（知识库问答，
 * P28 行为原样+附件）与「Issue 工作台」（FR-ISSUE-07 用户端对话工作台整体
 * 内嵌——需求提交侧栏+澄清+确认推送+讨论）。模式记忆会话级（含 /issues
 * 重定向落点）。服务端契约零变化，本页只是整合呈现层。
 */
const MODE_KEY = 'flexforge.assistant.mode';
type Mode = 'assistant' | 'issues';

const mode = ref<Mode>(readStoredMode());
const messages = ref<KbMessage[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied'>('loading');
const error = ref<string | null>(null);
const askError = ref<string | null>(null);
const asking = ref(false);
const sendResetKey = ref(0);
const clearing = ref(false);
const confirmClear = ref(false);

function readStoredMode(): Mode {
  try {
    return globalThis.sessionStorage?.getItem(MODE_KEY) === 'issues' ? 'issues' : 'assistant';
  } catch {
    return 'assistant';
  }
}

function switchMode(next: Mode): void {
  if (mode.value === next) {
    return;
  }
  mode.value = next;
  try {
    globalThis.sessionStorage?.setItem(MODE_KEY, next);
  } catch {
    /* 存储不可用则仅内存记忆 */
  }
}

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

async function send(question: string, files: File[]): Promise<void> {
  if (asking.value) {
    return;
  }
  asking.value = true;
  askError.value = null;
  messages.value = [
    ...messages.value,
    {
      id: `local-${messages.value.length}-u`,
      role: 'user',
      content: question,
      references: [],
      attachments: files.map((file, index) => ({
        id: `local-${messages.value.length}-${index}`,
        messageId: 'pending',
        filename: file.name,
        contentType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
      })),
    },
  ];
  try {
    const outcome = await askKb(question, files);
    messages.value = [
      ...messages.value.slice(0, -1),
      {
        id: `local-${messages.value.length}-u`,
        role: 'user',
        content: question,
        references: [],
        attachments: outcome.attachments,
      },
      {
        id: `local-${messages.value.length}-a`,
        role: 'assistant',
        content: outcome.answer,
        references: outcome.references,
        attachments: [],
      },
    ];
    sendResetKey.value += 1;
  } catch (e) {
    messages.value = messages.value.slice(0, -1);
    askError.value = apiErrorMessage(e, t('assistant.askFailed', '回答失败，请稍后重试'));
  } finally {
    asking.value = false;
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
      <h2 class="ff-page-title">
        {{
          mode === 'assistant' ? t('assistant.title', 'AI 助手') : t('issues.title', 'Issue 工作台')
        }}
      </h2>
      <div
        class="mode-switch"
        role="tablist"
        :aria-label="t('assistant.modeLabel', '模式切换')"
        data-testid="assistant-mode"
      >
        <span class="mode-thumb" :class="{ 'mode-thumb-right': mode === 'issues' }" />
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'assistant'"
          :class="{ 'mode-active': mode === 'assistant' }"
          data-testid="mode-assistant"
          @click="switchMode('assistant')"
        >
          {{ t('assistant.modeAssistant', 'AI 助手') }}
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'issues'"
          :class="{ 'mode-active': mode === 'issues' }"
          data-testid="mode-issues"
          @click="switchMode('issues')"
        >
          {{ t('assistant.modeIssues', 'Issue 工作台') }}
        </button>
      </div>
      <BaseButton
        v-if="mode === 'assistant' && messages.length > 0"
        variant="ghost"
        data-testid="assistant-clear"
        :disabled="asking || clearing"
        @click="confirmClear = true"
      >
        {{ t('assistant.clear', '清空会话') }}
      </BaseButton>
    </header>

    <IssueChatWorkbench v-if="mode === 'issues'" data-testid="assistant-issues-mode" />
    <template v-else>
      <StateView v-if="state !== 'ready'" :state="state" :message="error" />
      <template v-else>
        <div v-if="messages.length === 0 && !asking" class="chat-empty">
          <p>
            {{
              t('assistant.hint', '你好，我是 FlexForge AI 助手，可以解答企业制度与平台使用问题')
            }}
          </p>
        </div>
        <AssistantChat v-else :messages="messages" :asking="asking" />
        <p v-if="askError" class="ask-error" role="alert" data-testid="assistant-error">
          {{ askError }}
        </p>
        <AssistantComposer :asking="asking" :reset-key="sendResetKey" @send="send" />
      </template>
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
  align-items: center;
  gap: var(--ff-space-3);
  flex-wrap: wrap;
  margin-bottom: var(--ff-space-3);
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
.mode-switch {
  position: relative;
  display: inline-flex;
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  background: var(--ff-surface-muted);
  padding: 3px;
}
.mode-switch button {
  position: relative;
  z-index: 1;
  border: none;
  background: none;
  padding: var(--ff-space-1) var(--ff-space-3);
  border-radius: 999px;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
  cursor: pointer;
  white-space: nowrap;
}
.mode-switch .mode-active {
  color: var(--ff-primary-ink);
}
.mode-thumb {
  position: absolute;
  top: 3px;
  bottom: 3px;
  left: 3px;
  width: calc(50% - 3px);
  border-radius: 999px;
  background: var(--ff-primary);
  transition: transform var(--ff-motion-fast, 0.15s) var(--ff-ease, ease);
}
.mode-thumb-right {
  transform: translateX(100%);
}
@media (prefers-reduced-motion: reduce) {
  .mode-thumb {
    transition: none;
  }
}
</style>
