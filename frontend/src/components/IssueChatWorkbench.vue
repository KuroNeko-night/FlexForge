<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { apiErrorMessage } from '@/api/client';
import {
  createIssue,
  fetchComments,
  fetchIssue,
  fetchSpec,
  ISSUE_STATUS_LABELS,
  listIssues,
  publishIssue,
  type ClarifyBrief,
  type IssueComment,
  type IssueRecord,
  type SpecRevision,
} from '@/api/issues';
import IssueClarifyChat from '@/components/IssueClarifyChat.vue';
import { parseClarifyBrief } from '@/utils/clarifyBrief';
import IssueDiscussion from '@/components/IssueDiscussion.vue';
import IssueWorkbenchSidebar from '@/components/IssueWorkbenchSidebar.vue';
import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 用户端需求对话工作台（P23，FR-ISSUE-07）：纯 USER 视角——对话与自己的需求。
 * 侧栏 IssueWorkbenchSidebar（可折叠，已发布分组）；对话复用 IssueClarifyChat
 *（v3 口语化确认进流）；确认推送卡 + 讨论区 IssueDiscussion。开发者信息面
 * 不在本视图（S2 服务端收口）。
 */
const issues = ref<IssueRecord[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);

const sidebarCollapsed = ref(false);
const active = ref<IssueRecord | null>(null);
const activeBrief = ref<ClarifyBrief | null>(null);
const comments = ref<IssueComment[]>([]);

const creating = ref(false);
const createError = ref<string | null>(null);
const form = ref({ title: '', description: '' });
const publishing = ref(false);
const publishError = ref<string | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  try {
    issues.value = (await listIssues()) ?? [];
    loadError.value = null;
  } catch (e) {
    loadError.value = apiErrorMessage(e, '加载失败');
  } finally {
    loading.value = false;
  }
}

function syncIssue(next: IssueRecord): void {
  issues.value = issues.value.map((issue) => (issue.id === next.id ? next : issue));
}

/** 打开一条需求：刷新详情（发布态/状态）+ 简报（成稿后仍在）+ 讨论。 */
async function open(issue: IssueRecord): Promise<void> {
  active.value = issue;
  activeBrief.value = null;
  comments.value = [];
  publishError.value = null;
  const issueId = issue.id;
  try {
    const [detail, nextComments] = await Promise.all([
      fetchIssue(issueId),
      fetchComments(issueId).catch(() => [] as IssueComment[]),
    ]);
    if (active.value?.id !== issueId) {
      return;
    }
    syncIssue(detail);
    active.value = detail;
    comments.value = nextComments;
    const spec = await fetchSpec(issueId).catch(() => null);
    if (active.value?.id === issueId) {
      activeBrief.value = parseClarifyBrief(spec?.briefJson ?? null);
    }
  } catch {
    /* 详情失败保留列表态，子区各自空态 */
  }
}

/** 对话产出规格+简报：暂存简报（未发布时确认卡出现）。 */
function onSpecSaved(spec: SpecRevision, brief: ClarifyBrief | null): void {
  activeBrief.value = brief ?? parseClarifyBrief(spec.briefJson);
}

async function confirmPublish(): Promise<void> {
  if (!active.value || publishing.value) {
    return;
  }
  const issueId = active.value.id;
  publishing.value = true;
  publishError.value = null;
  try {
    const next = await publishIssue(issueId);
    syncIssue(next);
    // 陈旧守卫：推送期间切换到其他需求时不回写视图（审查 P3-6）
    if (active.value?.id === issueId) {
      active.value = next;
    }
  } catch (e) {
    publishError.value = apiErrorMessage(e, '确认推送失败，请稍后重试');
  } finally {
    publishing.value = false;
  }
}

async function submitCreate(): Promise<void> {
  if (creating.value || !form.value.title.trim() || !form.value.description.trim()) {
    return;
  }
  creating.value = true;
  createError.value = null;
  try {
    const created = await createIssue({
      title: form.value.title.trim(),
      description: form.value.description.trim(),
    });
    issues.value = [created, ...issues.value];
    form.value = { title: '', description: '' };
    await open(created);
  } catch (e) {
    createError.value = apiErrorMessage(e, '创建失败，请稍后重试');
  } finally {
    creating.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="workbench" data-testid="issue-chat-workbench">
    <IssueWorkbenchSidebar
      :issues="issues"
      :active-id="active?.id ?? null"
      :collapsed="sidebarCollapsed"
      :loading="loading"
      :error="loadError"
      @toggle="sidebarCollapsed = !sidebarCollapsed"
      @create="active = null"
      @select="open"
    />

    <div class="main">
      <!-- 新建需求表单（对话开始前的入口） -->
      <section v-if="!active" class="new-form" data-testid="new-requirement-form">
        <h3>描述你的需求</h3>
        <p class="new-hint">和 AI 一轮轮聊清楚，确认后推送给我方开发</p>
        <form class="ff-form-grid" @submit.prevent="submitCreate">
          <label class="field">
            标题
            <input
              v-model="form.title"
              data-testid="new-requirement-title"
              maxlength="120"
              required
            />
          </label>
          <label class="field field--full">
            想要什么
            <textarea
              v-model="form.description"
              data-testid="new-requirement-description"
              rows="4"
              maxlength="4000"
              required
            />
          </label>
          <BaseButton
            variant="primary"
            type="submit"
            :disabled="creating"
            data-testid="start-clarify"
          >
            {{ creating ? '创建中…' : '开始与 AI 梳理' }}
          </BaseButton>
        </form>
        <p v-if="createError" class="form-error" role="alert">{{ createError }}</p>
      </section>

      <!-- 对话视图：标题栏 + 澄清对话 + 确认卡 + 讨论 -->
      <template v-else>
        <header class="chat-head">
          <div class="chat-head-title">
            <h3>{{ active.title }}</h3>
            <span v-if="active.publishedAt" class="published-badge" data-testid="published-badge">
              已发布
            </span>
            <span class="status-chip">{{ ISSUE_STATUS_LABELS[active.status] }}</span>
          </div>
          <p class="chat-head-desc">{{ active.description }}</p>
        </header>

        <IssueClarifyChat
          :key="active.id"
          :issue-id="active.id"
          :can-clarify="true"
          @spec-saved="onSpecSaved"
        />

        <div
          v-if="activeBrief && !active.publishedAt"
          class="confirm-card"
          data-testid="confirm-card"
        >
          <h4>需求确认</h4>
          <p>{{ activeBrief.colloquial }}</p>
          <div class="confirm-actions">
            <BaseButton
              variant="primary"
              :disabled="publishing"
              data-testid="confirm-publish"
              @click="confirmPublish"
            >
              {{ publishing ? '推送中…' : '确认并推送' }}
            </BaseButton>
            <span class="confirm-hint">推送后进入开发者需求队列，可继续在讨论区补充</span>
          </div>
          <p v-if="publishError" class="form-error" role="alert">{{ publishError }}</p>
        </div>
        <p v-else-if="active.publishedAt" class="published-note">
          需求已确认推送 · 开发者可见（{{ ISSUE_STATUS_LABELS[active.status] }}）
        </p>

        <!-- :key 随需求重建（P24）：切换需求时输入草稿复位，不残留上一需求的评论草稿 -->
        <IssueDiscussion
          :key="active.id"
          :issue-id="active.id"
          :comments="comments"
          @reloaded="comments = $event"
        />
      </template>
    </div>
  </section>
</template>

<style scoped>
.workbench {
  display: flex;
  gap: var(--ff-space-4);
  align-items: stretch;
  min-height: 24rem;
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.new-form {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.new-form h3 {
  margin: 0;
}
.new-hint {
  margin: 0;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.field {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
  font-size: var(--ff-text-sm);
}
/* 网格行距由 gap 提供（审查 P3-1：去 margin 叠加）；按钮不随网格列拉伸 */
.new-form form .ff-btn {
  justify-self: start;
}
.field input,
.field textarea {
  padding: var(--ff-space-2);
  border-radius: var(--ff-radius-md);
}
.chat-head {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
}
.chat-head-title {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
}
.chat-head-title h3 {
  margin: 0;
}
.chat-head-desc {
  margin: 0;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.status-chip {
  padding: 0 var(--ff-space-2);
  border-radius: 999px;
  font-size: var(--ff-text-xs, 0.75rem);
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.published-badge {
  padding: 0 var(--ff-space-2);
  border-radius: 999px;
  font-size: var(--ff-text-xs, 0.75rem);
  background: color-mix(in srgb, var(--ff-primary) 14%, transparent);
  color: var(--ff-primary);
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
.confirm-card p {
  white-space: pre-wrap;
}
.confirm-actions {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
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
</style>
