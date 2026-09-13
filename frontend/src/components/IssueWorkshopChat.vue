<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import {
  clearWorkshopMessages,
  fetchWorkshopMessages,
  sendWorkshopMessage,
  type WorkshopMessage,
} from '@/api/issues';
import AppIcon from '@/components/AppIcon.vue';
import IssueWorkshopLog from '@/components/IssueWorkshopLog.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 需求工坊对话（FR-ISSUE-09，P30）：与 AI 助手同构的气泡对话——开场平台模板
 * 引导文案；澄清完成后 AI 调用 create_issue 工具，创建消息附"确认并推送"卡
 * （推送由父层执行；发布态以父层服务端列表 publishedIds 为准——跨挂载不
 * 复活，审查 P2-1）；会话按用户隔离、可清空。
 */
const props = defineProps<{ publishing: boolean; publishedIds?: Set<string> }>();
const emit = defineEmits<{ created: [issueId: string]; publish: [issueId: string] }>();

const messages = ref<WorkshopMessage[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);
const draft = ref('');
const sending = ref(false);
const sendError = ref<string | null>(null);
const clearing = ref(false);
const confirmClearOpen = ref(false);
const published = ref<Set<string>>(new Set());

const createdMessage = computed(
  () => messages.value.filter((m) => m.role === 'assistant' && m.issueId).at(-1) ?? null,
);

/** 确认卡可见性：本轮会话已推送 ∪ 父层服务端发布态（双源合并）。 */
const publishedMerged = computed(
  () => new Set([...published.value, ...(props.publishedIds ?? new Set())]),
);

async function load(): Promise<void> {
  loading.value = true;
  try {
    messages.value = await fetchWorkshopMessages();
    loadError.value = null;
  } catch (e) {
    loadError.value = apiErrorMessage(e, t('common.loadFailed', '加载失败'));
  } finally {
    loading.value = false;
  }
}

async function send(): Promise<void> {
  const text = draft.value.trim();
  if (sending.value || text === '') {
    return;
  }
  sending.value = true;
  sendError.value = null;
  try {
    const outcome = await sendWorkshopMessage(text);
    messages.value = [
      ...messages.value,
      { id: `local-u-${messages.value.length}`, role: 'user', content: text, issueId: null },
      {
        id: `local-a-${messages.value.length}`,
        role: 'assistant',
        content: outcome.reply,
        issueId: outcome.issueId,
      },
    ];
    draft.value = '';
    if (outcome.issueId) {
      emit('created', outcome.issueId);
    }
  } catch (e) {
    sendError.value =
      e instanceof ApiError ? e.message : t('issues.workshopSendFailed', '发送失败，请稍后重试');
  } finally {
    sending.value = false;
  }
}

/** Enter 发送；Shift+Enter 换行；IME 组态中的回车是选词不是提交。 */
function onDraftKeydown(event: KeyboardEvent): void {
  if (!event.shiftKey && !event.isComposing) {
    event.preventDefault();
    void send();
  }
}

async function submitClear(): Promise<void> {
  clearing.value = true;
  try {
    await clearWorkshopMessages();
    messages.value = [];
    published.value = new Set();
    confirmClearOpen.value = false;
  } catch (e) {
    sendError.value = apiErrorMessage(e, t('issues.workshopClearFailed', '清空失败，请稍后重试'));
  } finally {
    clearing.value = false;
  }
}

/** 父层推送成功后回写（收起确认卡改显示已推送）。 */
function markPublished(issueId: string): void {
  published.value = new Set([...published.value, issueId]);
}

onMounted(load);
defineExpose({ markPublished, reload: load });
</script>

<template>
  <div class="workshop" data-testid="workshop-chat">
    <header class="chat-head">
      <p class="hint">
        {{ t('issues.workshopHint', '和 AI 聊出你的需求，信息足够后它会自动创建需求') }}
      </p>
      <BaseButton
        v-if="messages.length > 0"
        variant="ghost"
        data-testid="workshop-clear"
        :disabled="sending || clearing"
        @click="confirmClearOpen = true"
      >
        {{ t('issues.workshopClear', '清空对话') }}
      </BaseButton>
    </header>

    <div v-if="loading" class="state">{{ t('common.loading', '加载中…') }}</div>
    <div v-else-if="loadError" class="state state-error">{{ loadError }}</div>
    <template v-else>
      <div v-if="messages.length === 0" class="chat-empty" data-testid="workshop-guide">
        <span class="avatar avatar-ai"><AppIcon name="chat" :size="13" /></span>
        <div class="bubble">
          <span class="role-label">AI</span>
          <p class="bubble-text">
            {{
              t(
                'issues.workshopGuide',
                '你好，想做一个什么需求？可以按这个模板说：我要管理【什么数据】，字段有【…】，规则是【…】，验收标准是【…】',
              )
            }}
          </p>
        </div>
      </div>
      <IssueWorkshopLog v-else :messages="messages" :sending="sending" />

      <div
        v-if="createdMessage?.issueId && !publishedMerged.has(createdMessage.issueId)"
        class="confirm-card"
        data-testid="workshop-created-card"
      >
        <h4>{{ t('issues.confirmHeading', '需求确认') }}</h4>
        <p>
          {{
            t('issues.workshopCreatedHint', 'AI 已按对话创建需求并生成规格草稿，确认后推送给开发者')
          }}
        </p>
        <div class="confirm-actions">
          <BaseButton
            variant="primary"
            :disabled="props.publishing"
            data-testid="workshop-publish"
            @click="emit('publish', createdMessage.issueId)"
          >
            {{ t('issues.confirmPublish', '确认并推送') }}
          </BaseButton>
          <span class="confirm-hint">{{
            t('issues.workshopPublishHint', '也可稍后从右侧需求列表进入对话再推送')
          }}</span>
        </div>
      </div>
      <p v-else-if="createdMessage?.issueId" class="published-note">
        {{ t('issues.publishedNote', '需求已确认推送 · 开发者可见') }}
      </p>

      <p v-if="sendError" class="send-error" role="alert" data-testid="workshop-error">
        {{ sendError }}
      </p>
      <div class="composer">
        <textarea
          v-model="draft"
          rows="2"
          :placeholder="t('issues.workshopPlaceholder', '描述你的需求…')"
          data-testid="workshop-input"
          :disabled="sending"
          @keydown.enter="onDraftKeydown"
        />
        <BaseButton
          variant="primary"
          :aria-label="t('common.send', '发送')"
          :disabled="sending || draft.trim() === ''"
          data-testid="workshop-send"
          @click="send"
        >
          <AppIcon name="send" :size="16" />
        </BaseButton>
      </div>
      <p class="composer-hint">
        {{ t('assistant.composerHint', 'Enter 发送，Shift + Enter 换行') }}
      </p>
    </template>

    <div v-if="confirmClearOpen" class="clear-row" data-testid="workshop-clear-confirm">
      <span>{{ t('issues.workshopClearConfirm', '清空工坊对话？不可恢复') }}</span>
      <BaseButton variant="ghost" :disabled="clearing" @click="confirmClearOpen = false">
        {{ t('common.cancel', '取消') }}
      </BaseButton>
      <BaseButton
        variant="primary"
        :disabled="clearing"
        data-testid="workshop-clear-ok"
        @click="submitClear"
      >
        {{ t('issues.workshopClear', '清空对话') }}
      </BaseButton>
    </div>
  </div>
</template>

<style scoped>
.workshop {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  min-height: 0;
}
.chat-head {
  display: flex;
  align-items: center;
  gap: var(--ff-space-3);
}
.hint {
  margin: 0;
  flex: 1;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.state {
  color: var(--ff-text-muted);
  text-align: center;
  padding: var(--ff-space-3) 0;
}
.state-error {
  color: var(--ff-danger);
}
.chat-empty {
  display: flex;
  gap: var(--ff-space-2);
  align-items: flex-start;
}
.chat-empty .avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--ff-primary);
  color: var(--ff-primary-ink);
}
.chat-empty .bubble {
  max-width: min(72ch, 82%);
  background: var(--ff-surface);
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-md);
  padding: var(--ff-space-2) var(--ff-space-3);
}
.chat-empty .role-label {
  display: block;
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
  margin-bottom: var(--ff-space-1);
}
.confirm-card {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  padding: var(--ff-space-3);
  border: 1px solid color-mix(in srgb, var(--ff-primary) 40%, transparent);
  border-radius: var(--ff-radius-md);
  background: var(--ff-primary-soft);
}
.confirm-card h4,
.confirm-card p {
  margin: 0;
}
.confirm-actions {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
}
.confirm-hint {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.published-note {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.send-error {
  color: var(--ff-danger);
  font-size: var(--ff-text-sm);
  margin: 0;
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
  margin: 0;
}
.clear-row {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  padding: var(--ff-space-2);
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
  font-size: var(--ff-text-sm);
}
</style>
