<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';

import { apiErrorMessage } from '@/api/client';
import {
  clarifyIssue,
  type ClarifyBrief,
  type ClarifyOutcome,
  type SpecRevision,
} from '@/api/issues';
import AppIcon from '@/components/AppIcon.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 需求澄清对话（P15 建面，P21 现代化重构）：clarify 多轮问答（FR-ISSUE-03，
 * 提示词 v2——信息不足 AI 先提问）。分角色气泡+进入动画+打字指示；IME 组态
 * 守卫与陈旧响应守卫保留；权限由父层判定（canClarify），服务端为边界。
 * 父层以 :key=issue.id 挂载，切换 Issue 即整体复位。P23（提示词 v3）：规格成稿
 * 时口语化确认（brief.colloquial）作为 AI 消息进对话流，简报全量经 specSaved
 * 上抛（父层决定确认推送卡/开发者分区展示）。
 */
const props = defineProps<{ issueId: string; canClarify: boolean }>();
const emit = defineEmits<{
  specSaved: [spec: SpecRevision, brief: ClarifyBrief | null];
}>();

interface ChatMessage {
  role: 'ai' | 'user';
  text: string;
}

const chat = ref<ChatMessage[]>([]);
const answer = ref('');
const clarifying = ref(false);
const chatError = ref<string | null>(null);
const chatLog = ref<HTMLDivElement | null>(null);

// 新消息/打字态落到底部视野（超出容器高度时免手动滚屏）
watch(
  () => [chat.value.length, clarifying.value] as const,
  async () => {
    await nextTick();
    if (chatLog.value) {
      chatLog.value.scrollTop = chatLog.value.scrollHeight;
    }
  },
);

/** clarify 结果落对话流：用户回答 → AI 追问/规格草稿提示（无追问给兜底文案）。 */
function applyOutcome(text: string | null, outcome: ClarifyOutcome): void {
  if (text !== null) {
    chat.value = [...chat.value, { role: 'user', text }];
  }
  if (outcome.specProduced) {
    emit('specSaved', outcome.spec as SpecRevision, outcome.brief);
    if (outcome.brief) {
      // v3：口语化确认先入对话流（面向用户的成稿复述）
      chat.value = [...chat.value, { role: 'ai', text: outcome.brief.colloquial }];
    }
    chat.value = [
      ...chat.value,
      {
        role: 'ai',
        text: t('issues.draftNotice', '已按当前回答生成规格草稿，可在下方规格区查看与继续迭代。'),
      },
    ];
    return;
  }
  const replies =
    outcome.questions.length > 0
      ? outcome.questions
      : [t('issues.noFollowUp', '模型未返回追问，可重试或让开发者手工编写规格。')];
  for (const reply of replies) {
    chat.value = [...chat.value, { role: 'ai', text: reply }];
  }
}

async function sendClarify(text: string | null): Promise<void> {
  const issueId = props.issueId;
  clarifying.value = true;
  chatError.value = null;
  try {
    const outcome = await clarifyIssue(issueId, text ?? undefined);
    if (props.issueId === issueId) {
      applyOutcome(text, outcome);
    }
  } catch (e) {
    if (props.issueId === issueId) {
      chatError.value = apiErrorMessage(e, t('issues.aiFailed', 'AI 调用失败，请稍后重试'));
    }
  } finally {
    clarifying.value = false;
  }
}

function submitAnswer(): void {
  const text = answer.value.trim();
  if (clarifying.value || text === '') {
    return;
  }
  answer.value = '';
  void sendClarify(text);
}

/** Enter 发送；Shift+Enter 换行；IME 组态中的回车是选词不是提交（PR #34 审查 P2）。 */
function onAnswerKeydown(event: KeyboardEvent): void {
  if (!event.shiftKey && !event.isComposing) {
    event.preventDefault();
    submitAnswer();
  }
}
</script>

<template>
  <div class="clarify" data-testid="clarify-chat">
    <header class="clarify-head">
      <span class="avatar avatar-ai" aria-hidden="true">
        <AppIcon name="chat" :size="15" />
      </span>
      <div class="clarify-title">
        <strong>{{ t('issues.clarifyTitle', '需求澄清') }}</strong>
        <span>{{ t('issues.clarifySub', 'AI 助手逐步提问，补齐规格草稿') }}</span>
      </div>
    </header>

    <template v-if="canClarify">
      <div v-if="chat.length === 0 && !clarifying" class="chat-empty">
        <p>{{ t('issues.clarifyHint', '回答 AI 的追问，规格草稿会随对话逐步成形') }}</p>
        <BaseButton variant="primary" data-testid="clarify-start" @click="sendClarify(null)">
          {{ t('issues.startClarifyBtn', '开始 AI 澄清') }}
        </BaseButton>
      </div>
      <div v-else ref="chatLog" class="chat-log" data-testid="clarify-log">
        <div
          v-for="(message, index) in chat"
          :key="index"
          class="message"
          :data-role="message.role"
        >
          <span class="avatar" :class="message.role === 'ai' ? 'avatar-ai' : 'avatar-user'">
            <AppIcon v-if="message.role === 'ai'" name="chat" :size="13" />
            <template v-else>{{ t('issues.me', '我') }}</template>
          </span>
          <div class="bubble">
            <span class="role-label">{{
              message.role === 'ai' ? 'AI' : t('issues.me', '我')
            }}</span>
            <p>{{ message.text }}</p>
          </div>
        </div>
        <div v-if="clarifying" class="message" data-role="ai" data-testid="clarify-typing">
          <span class="avatar avatar-ai"><AppIcon name="chat" :size="13" /></span>
          <div class="bubble bubble-typing" :aria-label="t('issues.aiThinking', 'AI 正在思考')">
            <span class="dot" />
            <span class="dot" />
            <span class="dot" />
          </div>
        </div>
      </div>
      <div v-if="chat.length > 0" class="composer">
        <textarea
          v-model="answer"
          rows="2"
          :placeholder="t('issues.answerPlaceholder', '回答 AI 的追问…')"
          data-testid="clarify-input"
          :disabled="clarifying"
          @keydown.enter="onAnswerKeydown"
        />
        <BaseButton
          variant="primary"
          :aria-label="t('common.send', '发送')"
          :disabled="clarifying || answer.trim() === ''"
          data-testid="clarify-send"
          @click="submitAnswer"
        >
          <AppIcon name="send" :size="16" />
        </BaseButton>
      </div>
      <p class="composer-hint">{{ t('issues.composerHint', 'Enter 发送，Shift + Enter 换行') }}</p>
      <p v-if="chatError" class="form-error" role="alert">{{ chatError }}</p>
    </template>
    <p v-else class="chat-hint">
      {{ t('issues.clarifyPermission', 'AI 澄清由 Issue 作者或开发者发起。') }}
    </p>
  </div>
</template>

<style scoped>
.clarify {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.clarify-head {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
}
.clarify-title {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.clarify-title span {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 999px;
  flex: none;
  font-size: var(--ff-text-sm);
}
.avatar-ai {
  width: 2rem;
  height: 2rem;
  border-radius: var(--ff-radius-sm);
  background: color-mix(in srgb, var(--ff-primary) 14%, transparent);
  color: var(--ff-primary);
}
.avatar-user {
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ff-space-2);
  padding: var(--ff-space-4) var(--ff-space-3);
  border: 1px dashed var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  text-align: center;
}
.chat-empty p {
  margin: 0;
  color: var(--ff-text-muted);
}
.chat-log {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
  max-height: 18rem;
  overflow-y: auto;
  padding: var(--ff-space-1) 0;
}
.message {
  display: flex;
  align-items: flex-end;
  gap: var(--ff-space-2);
  animation: ff-message-in var(--ff-motion-base) both;
}
.message[data-role='user'] {
  flex-direction: row-reverse;
}
.bubble {
  max-width: min(85%, 34rem);
  padding: var(--ff-space-2) var(--ff-space-3);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
}
.message[data-role='user'] .bubble {
  background: color-mix(in srgb, var(--ff-primary) 12%, transparent);
}
.role-label {
  display: block;
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
  margin-bottom: var(--ff-space-1);
}
.bubble p {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.bubble-typing {
  display: flex;
  gap: var(--ff-space-1);
  padding: var(--ff-space-2) var(--ff-space-3);
}
.dot {
  width: 0.375rem;
  height: 0.375rem;
  border-radius: 999px;
  background: var(--ff-text-muted);
  animation: ff-dot-pulse calc(var(--ff-motion-base) * 4) infinite;
}
.dot:nth-child(2) {
  animation-delay: calc(var(--ff-motion-base) * 0.6);
}
.dot:nth-child(3) {
  animation-delay: calc(var(--ff-motion-base) * 1.2);
}
.composer {
  display: flex;
  align-items: flex-end;
  gap: var(--ff-space-2);
}
.composer textarea {
  flex: 1;
  padding: var(--ff-space-2);
  resize: vertical;
  border-radius: var(--ff-radius-md);
}
.composer-hint {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.chat-hint {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
@keyframes ff-message-in {
  from {
    opacity: 0;
    transform: translateY(0.375rem);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
@keyframes ff-dot-pulse {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}
</style>
