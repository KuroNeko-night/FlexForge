<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { apiErrorMessage } from '@/api/client';
import {
  fetchComments,
  fetchIssue,
  fetchSpec,
  issueStatusLabel,
  listIssues,
  publishIssue,
  type ClarifyBrief,
  type IssueComment,
  type IssueRecord,
  type SpecRevision,
} from '@/api/issues';
import IssueClarifyChat from '@/components/IssueClarifyChat.vue';
import IssueDiscussion from '@/components/IssueDiscussion.vue';
import IssueWorkbenchSidebar from '@/components/IssueWorkbenchSidebar.vue';
import IssueWorkshopChat from '@/components/IssueWorkshopChat.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import { parseClarifyBrief } from '@/utils/clarifyBrief';
import { t } from '@/registry/localeRegistry';

/**
 * Issue 工作台视图（P30 整合页 Issue 模式）：与 AI 助手同构布局——居中工坊
 * 对话（默认）+ 右侧"我的需求"侧栏（左侧已有全局导航）；侧栏选中进入
 * per-issue 详情（澄清对话/确认卡/讨论区，P23 语义不变，自原 IssueChatWorkbench
 * 移植）；创建统一走工坊对话（AI 工具建需求），侧栏"新建需求"回到工坊。
 */
const issues = ref<IssueRecord[]>([]);
const loading = ref(true);
const loadError = ref<string | null>(null);
const sidebarCollapsed = ref(false);
const active = ref<IssueRecord | null>(null);
const activeBrief = ref<ClarifyBrief | null>(null);
const comments = ref<IssueComment[]>([]);
const publishing = ref(false);
const publishError = ref<string | null>(null);
const workshopRef = ref<InstanceType<typeof IssueWorkshopChat> | null>(null);

/** 已发布需求 id 集（服务端列表为准——工坊确认卡跨挂载不复活，审查 P2-1）。 */
const publishedIds = computed(
  () => new Set(issues.value.filter((issue) => issue.publishedAt).map((issue) => issue.id)),
);

async function load(): Promise<void> {
  loading.value = true;
  try {
    issues.value = (await listIssues()) ?? [];
    loadError.value = null;
  } catch (e) {
    loadError.value = apiErrorMessage(e, t('common.loadFailed', '加载失败'));
  } finally {
    loading.value = false;
  }
}

function syncIssue(next: IssueRecord): void {
  issues.value = issues.value.map((issue) => (issue.id === next.id ? next : issue));
}

/** 工坊创建了新需求：刷新侧栏（留在工坊对话——确认卡与推送都在对话内，
 * 侧栏新条目即时可见，点击可进入详情）。 */
async function onWorkshopCreated(): Promise<void> {
  await load();
}

/** 工坊确认卡推送。 */
async function onWorkshopPublish(issueId: string): Promise<void> {
  publishing.value = true;
  publishError.value = null;
  try {
    const next = await publishIssue(issueId);
    syncIssue(next);
    workshopRef.value?.markPublished(issueId);
  } catch (e) {
    publishError.value = apiErrorMessage(e, t('issues.publishFailed', '确认推送失败，请稍后重试'));
  } finally {
    publishing.value = false;
  }
}

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
    if (active.value?.id === issueId) {
      active.value = next;
    }
  } catch (e) {
    publishError.value = apiErrorMessage(e, t('issues.publishFailed', '确认推送失败，请稍后重试'));
  } finally {
    publishing.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="workshop-view" data-testid="issue-workshop-view">
    <div class="main">
      <template v-if="!active">
        <IssueWorkshopChat
          ref="workshopRef"
          :publishing="publishing"
          :published-ids="publishedIds"
          @created="onWorkshopCreated"
          @publish="onWorkshopPublish"
        />
        <p v-if="publishError" class="form-error" role="alert" data-testid="workshop-publish-error">
          {{ publishError }}
        </p>
      </template>
      <template v-else>
        <!-- P32 分区重排：详情自管滚动；sticky 头部保上下文，澄清/确认/讨论三区卡片分组 -->
        <div class="detail" data-testid="workshop-issue-detail">
          <header class="detail-head">
            <div class="detail-head-row">
              <BaseButton variant="ghost" data-testid="workshop-back" @click="active = null">
                {{ t('issues.workshopBack', '返回工坊') }}
              </BaseButton>
              <h3>{{ active.title }}</h3>
              <span v-if="active.publishedAt" class="published-badge" data-testid="published-badge">
                {{ t('issues.published', '已发布') }}
              </span>
              <span class="status-chip">{{ issueStatusLabel(active.status) }}</span>
            </div>
            <p class="detail-desc">{{ active.description }}</p>
          </header>

          <section class="detail-section" data-testid="issue-detail-clarify">
            <IssueClarifyChat
              :key="active.id"
              :issue-id="active.id"
              :can-clarify="true"
              @spec-saved="onSpecSaved"
            />
          </section>

          <div
            v-if="activeBrief && !active.publishedAt"
            class="confirm-card"
            data-testid="confirm-card"
          >
            <h4>{{ t('issues.confirmHeading', '需求确认') }}</h4>
            <p>{{ activeBrief.colloquial }}</p>
            <div class="confirm-actions">
              <BaseButton
                variant="primary"
                :disabled="publishing"
                data-testid="confirm-publish"
                @click="confirmPublish"
              >
                {{
                  publishing
                    ? t('issues.publishing', '推送中…')
                    : t('issues.confirmPublish', '确认并推送')
                }}
              </BaseButton>
              <span class="confirm-hint">{{
                t('issues.confirmHint', '推送后进入开发者需求队列，可继续在讨论区补充')
              }}</span>
            </div>
            <p v-if="publishError" class="form-error" role="alert">{{ publishError }}</p>
          </div>
          <p v-else-if="active.publishedAt" class="published-note">
            {{ t('issues.publishedNote', '需求已确认推送 · 开发者可见') }}（{{
              issueStatusLabel(active.status)
            }}）
          </p>

          <section class="detail-section" data-testid="issue-detail-discussion">
            <IssueDiscussion
              :key="active.id"
              :issue-id="active.id"
              :comments="comments"
              @reloaded="comments = $event"
            />
          </section>
        </div>
      </template>
    </div>

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
  </div>
</template>

<style scoped>
.workshop-view {
  flex: 1;
  display: flex;
  gap: var(--ff-space-4);
  align-items: stretch;
  min-height: 0;
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
  /* P32 滚动收敛：工坊对话与详情各自管理内部滚动，主列不产生外层滚动 */
  min-height: 0;
  overflow: hidden;
}
.detail {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
/* sticky 头部卡：滚动中标题/状态/描述不丢上下文（P32 排版反馈核心项） */
.detail-head {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
  padding: var(--ff-space-3);
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  box-shadow: var(--ff-shadow-1);
}
.detail-head-row {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
}
.detail-desc {
  margin: 0;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
/* 分区卡片：澄清/讨论两组的视觉边界（组件自带小节标题，不重复加键） */
.detail-section {
  padding: var(--ff-space-3);
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
}
/* 讨论组件原为分隔条形态（上边框+上内距），入卡片后去掉避免双重边界 */
.detail-section :deep(.discussion) {
  padding-top: 0;
  border-top: none;
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
.confirm-card p,
.detail-head-row h3 {
  margin: 0;
}
.confirm-card p {
  white-space: pre-wrap;
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
.form-error {
  color: var(--ff-danger);
  font-size: var(--ff-text-sm);
  margin: 0;
}
</style>
