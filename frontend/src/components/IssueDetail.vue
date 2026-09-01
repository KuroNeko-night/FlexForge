<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';

import { apiErrorMessage } from '@/api/client';
import {
  clarifyIssue,
  fetchComments,
  fetchSpec,
  ISSUE_STATUS_LABELS,
  type ClarifyOutcome,
  type IssueComment,
  type IssueRecord,
  type SpecRevision,
} from '@/api/issues';
import { session } from '@/auth/token';
import IssueComments from '@/components/IssueComments.vue';
import IssueDevPanel from '@/components/IssueDevPanel.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * Issue 详情（P15）：AI 对话（clarify 多轮问答，FR-ISSUE-03）+ 评论 +
 * 规格状态展示；开发者面板（迁移/规格编辑/预览/生成）由 IssueDevPanel 承载。
 * clarify 权限=作者或开发者（后端同口径）；角色仅控制入口显隐，安全边界在服务端。
 */
const props = defineProps<{ issue: IssueRecord }>();
const emit = defineEmits<{
  updated: [issue: IssueRecord];
  specSaved: [spec: SpecRevision];
}>();

interface ChatMessage {
  role: 'ai' | 'user';
  text: string;
}

const comments = ref<IssueComment[]>([]);
const spec = ref<SpecRevision | null>(null);
const chat = ref<ChatMessage[]>([]);
const answer = ref('');
const clarifying = ref(false);
const chatError = ref<string | null>(null);
const chatLog = ref<HTMLUListElement | null>(null);

// 新消息落到底部视野（多轮追问超出容器高度时免手动滚屏）
watch(
  () => chat.value.length,
  async () => {
    await nextTick();
    if (chatLog.value) {
      chatLog.value.scrollTop = chatLog.value.scrollHeight;
    }
  },
);

const isDeveloper = computed(() => session.user?.roles.includes('DEVELOPER') ?? false);
const canClarify = computed(
  () => isDeveloper.value || session.user?.username === props.issue.createdBy,
);

async function loadDetail(): Promise<void> {
  const issueId = props.issue.id;
  try {
    const [nextComments, nextSpec] = await Promise.all([
      fetchComments(issueId),
      fetchSpec(issueId),
    ]);
    // 陈旧响应守卫：切换 Issue 期间的返回不落当前面板
    if (props.issue.id !== issueId) {
      return;
    }
    comments.value = nextComments;
    spec.value = nextSpec;
  } catch {
    /* 详情子区加载失败不毁面板：评论区/规格区各自显示空态 */
  }
}

/** 切换 Issue 即整体复位对话/输入态并重载子区（PR #34 审查 P2：组件复用防串台）。 */
watch(
  () => props.issue.id,
  () => {
    chat.value = [];
    answer.value = '';
    chatError.value = null;
    void loadDetail();
  },
  { immediate: true },
);

/** clarify 结果落对话流：用户回答 → AI 追问/规格草稿提示（无追问给兜底文案）。 */
function applyClarifyOutcome(text: string | null, outcome: ClarifyOutcome): void {
  if (text !== null) {
    chat.value = [...chat.value, { role: 'user', text }];
  }
  if (outcome.specProduced) {
    spec.value = outcome.spec;
    chat.value = [
      ...chat.value,
      { role: 'ai', text: '已按当前回答生成规格草稿，可在下方规格区查看与继续迭代。' },
    ];
    return;
  }
  const replies =
    outcome.questions.length > 0
      ? outcome.questions
      : ['模型未返回追问，可重试或让开发者手工编写规格。'];
  for (const reply of replies) {
    chat.value = [...chat.value, { role: 'ai', text: reply }];
  }
}

async function sendClarify(text: string | null): Promise<void> {
  const issueId = props.issue.id;
  clarifying.value = true;
  chatError.value = null;
  try {
    const outcome = await clarifyIssue(issueId, text ?? undefined);
    if (props.issue.id === issueId) {
      applyClarifyOutcome(text, outcome);
    }
  } catch (e) {
    if (props.issue.id === issueId) {
      chatError.value = apiErrorMessage(e, 'AI 调用失败，请稍后重试');
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

/** IME 组态中的回车是选词不是提交（PR #34 审查 P2：中文输入误发答案）。 */
function onAnswerEnter(event: KeyboardEvent): void {
  if (!event.isComposing) {
    submitAnswer();
  }
}

/** 开发者面板保存规格：本地同步 + 透传给父级（列表/详情共用一份规格状态）。 */
function onSpecSaved(next: SpecRevision): void {
  spec.value = next;
  emit('specSaved', next);
}
</script>

<template>
  <article class="issue-detail" data-testid="issue-detail">
    <header class="detail-head">
      <h3>{{ issue.title }}</h3>
      <span class="status-badge" :data-status="issue.status">
        {{ ISSUE_STATUS_LABELS[issue.status] }}
      </span>
    </header>
    <p class="detail-meta">
      {{ issue.createdBy }} 创建 · 指派：{{ issue.assignedTo ?? '—' }} ·
      {{ issue.labels.length > 0 ? issue.labels.join('、') : '无标签' }}
    </p>
    <p class="detail-desc">{{ issue.description }}</p>

    <ComponentCard title="需求澄清对话" subtitle="回答 AI 追问，逐步生成规格草稿">
      <div v-if="canClarify" class="chat" data-testid="clarify-chat">
        <ul v-if="chat.length > 0" ref="chatLog" class="chat-log">
          <li v-for="(message, index) in chat" :key="index" :data-role="message.role">
            {{ message.text }}
          </li>
        </ul>
        <p v-else class="chat-hint">尚未开始：点击下方按钮让 AI 就需求提问。</p>
        <div class="chat-input">
          <textarea
            v-model="answer"
            rows="2"
            placeholder="回答 AI 的追问…"
            :disabled="clarifying"
            @keydown.enter.prevent="onAnswerEnter"
          />
          <BaseButton
            v-if="chat.length === 0"
            variant="primary"
            :disabled="clarifying"
            data-testid="clarify-start"
            @click="sendClarify(null)"
          >
            {{ clarifying ? 'AI 思考中…' : '开始 AI 澄清' }}
          </BaseButton>
          <BaseButton
            v-else
            variant="primary"
            :disabled="clarifying || answer.trim() === ''"
            @click="submitAnswer"
          >
            {{ clarifying ? 'AI 思考中…' : '提交回答' }}
          </BaseButton>
        </div>
        <p v-if="chatError" class="form-error" role="alert">{{ chatError }}</p>
      </div>
      <p v-else class="chat-hint">AI 澄清由 Issue 作者或开发者发起。</p>
    </ComponentCard>

    <IssueDevPanel
      v-if="isDeveloper"
      :issue="issue"
      :spec="spec"
      @updated="emit('updated', $event)"
      @spec-saved="onSpecSaved"
    />
    <p v-else-if="spec" class="spec-summary">
      规格草稿：修订 {{ spec.revision }}，{{ spec.valid ? '校验通过' : '校验未通过' }}
    </p>

    <!-- :key 防 Issue 复用时 A 的评论草稿落到 B（审查 P2-4，specDraft 同型） -->
    <IssueComments
      :key="issue.id"
      :issue-id="issue.id"
      :comments="comments"
      @reloaded="comments = $event"
    />
  </article>
</template>

<style scoped>
.issue-detail {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.detail-head {
  display: flex;
  align-items: center;
  gap: var(--ff-space-3);
}
.detail-head h3 {
  margin: 0;
}
.detail-meta,
.detail-desc {
  margin: 0;
}
.detail-meta {
  color: var(--ff-text-muted);
}
.detail-desc {
  white-space: pre-wrap;
}
.status-badge {
  padding: var(--ff-space-1) var(--ff-space-2);
  border-radius: 999px;
  font-size: var(--ff-text-sm);
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.status-badge[data-status='APPROVED'],
.status-badge[data-status='TESTED'],
.status-badge[data-status='DONE'] {
  background: color-mix(in srgb, var(--ff-primary) 14%, transparent);
  color: var(--ff-primary);
}
.status-badge[data-status='RETURNED'],
.status-badge[data-status='DEV_FAILED'],
.status-badge[data-status='FEEDBACK'] {
  background: var(--ff-danger-bg);
  color: var(--ff-danger);
}
.chat {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.chat-log {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  max-height: 16rem;
  overflow-y: auto;
}
.chat-log li {
  max-width: 85%;
  padding: var(--ff-space-2) var(--ff-space-3);
  border-radius: var(--ff-radius-md);
}
.chat-log li[data-role='ai'] {
  background: var(--ff-surface-muted);
}
.chat-log li[data-role='user'] {
  background: color-mix(in srgb, var(--ff-primary) 12%, transparent);
  margin-left: auto;
}
.chat-hint,
.spec-summary,
.comment-list {
  margin: 0;
}
.chat-hint,
.spec-summary {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.chat-input {
  display: flex;
  gap: var(--ff-space-2);
  align-items: flex-end;
}
.chat-input textarea {
  flex: 1;
  padding: var(--ff-space-2);
  resize: vertical;
}
</style>
