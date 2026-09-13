<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { ApiError, apiErrorMessage } from '@/api/client';
import {
  createIssue,
  issueStatusLabel,
  listIssues,
  type IssueRecord,
  type IssueStatusName,
} from '@/api/issues';
import IssueDetail from '@/components/IssueDetail.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';
import StateView from '@/components/StateView.vue';
import { session } from '@/auth/token';
import { t } from '@/registry/localeRegistry';

/**
 * Issue 工作台（P15，FR-ISSUE-01..06 前端消费面）：列表 + 筛选 + 创建，
 * 选中后由 IssueDetail 承载 AI 对话（clarify）/规格/迁移/生成与评论。
 * P23（FR-ISSUE-07）角色分置；P29 整合：纯 USER 的对话工作台迁至 AI 助手
 * 整合页（/assistant Issue 模式），本视图直访时重定向；开发者/管理员保留
 * 完整信息面；安全边界在服务端（本分支只是体验层）。
 */
const router = useRouter();
const STATUS_OPTIONS: IssueStatusName[] = [
  'SUBMITTED',
  'APPROVED',
  'RETURNED',
  'IN_TESTING',
  'DEV_FAILED',
  'TESTED',
  'FEEDBACK',
  'DONE',
  'CLOSED',
];

const issues = ref<IssueRecord[]>([]);
const selected = ref<IssueRecord | null>(null);
const statusFilter = ref<IssueStatusName | ''>('');
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);

/** 会话身份已知前的加载态（子路由挂载先于父壳 fetchMe 回填——P29 审查 P1-1：
 * user 为 null 时不得判定为纯 USER，否则开发者硬刷新被误重定向）。 */
const userKnown = computed(() => session.user !== null);

/** 纯 USER 角色：体验入口在 AI 助手整合页（P29），身份回填后重定向。 */
const userOnly = computed(
  () =>
    session.user !== null &&
    !session.user.roles.includes('DEVELOPER') &&
    !session.user.roles.includes('ADMIN'),
);

/** 重定向只对"已确认的纯 USER"生效（身份到达前后各判一次）。 */
function redirectToAssistantIfUser(): void {
  if (userOnly.value) {
    try {
      globalThis.sessionStorage?.setItem('flexforge.assistant.mode', 'issues');
    } catch {
      /* 存储不可用则落助手模式 */
    }
    void router.replace('/assistant');
  }
}

const drawerOpen = ref(false);
const creating = ref(false);
const formError = ref<string | null>(null);
const form = ref({ title: '', description: '', labels: '' });

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    issues.value = await listIssues(statusFilter.value || undefined);
    state.value = issues.value.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

/** 详情变更后回写列表行（迁移/生成/clarify 均以服务端返回为准）。 */
function syncIssue(next: IssueRecord): void {
  issues.value = issues.value.map((issue) => (issue.id === next.id ? next : issue));
  if (selected.value?.id === next.id) {
    selected.value = next;
  }
}

async function submitCreate(): Promise<void> {
  if (creating.value || !form.value.title || !form.value.description) {
    return;
  }
  creating.value = true;
  formError.value = null;
  try {
    const labels = form.value.labels
      .split(/[,，\s]+/)
      .map((label) => label.trim())
      .filter(Boolean);
    const created = await createIssue({ ...form.value, labels });
    drawerOpen.value = false;
    form.value = { title: '', description: '', labels: '' };
    await load();
    selected.value = created;
  } catch (e) {
    formError.value = apiErrorMessage(e, t('common.createFailed', '创建失败，请稍后重试'));
  } finally {
    creating.value = false;
  }
}

onMounted(() => {
  redirectToAssistantIfUser();
  if (!userOnly.value) {
    void load();
  }
});

watch(userOnly, () => {
  if (userOnly.value) {
    redirectToAssistantIfUser();
  } else if (userKnown.value && state.value === 'loading') {
    void load();
  }
});
</script>

<template>
  <section class="issues-view" data-testid="issues-view">
    <StateView v-if="!userKnown" state="loading" />
    <template v-else-if="!userOnly">
      <header class="issues-header">
        <h2 class="ff-page-title">{{ t('issues.title', 'Issue 工作台') }}</h2>
        <div class="issues-actions">
          <label class="filter-label">
            {{ t('issues.statusLabel', '状态') }}
            <select v-model="statusFilter" data-testid="status-filter" @change="load">
              <option value="">{{ t('common.all', '全部') }}</option>
              <option v-for="status in STATUS_OPTIONS" :key="status" :value="status">
                {{ issueStatusLabel(status) }}
              </option>
            </select>
          </label>
          <BaseButton @click="load">{{ t('common.refresh', '刷新') }}</BaseButton>
          <BaseButton variant="primary" @click="drawerOpen = true">
            {{ t('issues.create', '新建 Issue') }}
          </BaseButton>
        </div>
      </header>
      <StateView v-if="state !== 'ready'" :state="state" :message="error">
        <p v-if="state === 'empty'">{{ t('issues.empty', '尚无 Issue，点击右上角新建') }}</p>
      </StateView>
      <div v-else class="issues-layout">
        <ul class="issue-list" data-testid="issue-list">
          <li v-for="issue in issues" :key="issue.id">
            <button
              type="button"
              class="issue-item"
              :class="{ active: selected?.id === issue.id }"
              :data-testid="`issue-item-${issue.id}`"
              @click="selected = issue"
            >
              <span class="issue-item-title">{{ issue.title }}</span>
              <span class="issue-item-meta">
                <span class="status-badge" :data-status="issue.status">
                  {{ issueStatusLabel(issue.status) }}
                </span>
                <span>{{ issue.createdBy }}</span>
              </span>
            </button>
          </li>
        </ul>
        <IssueDetail v-if="selected" :issue="selected" @updated="syncIssue" />
        <p v-else class="issue-empty-hint">
          {{ t('issues.selectHint', '从右侧需求列表选择一条，查看详情与 AI 对话') }}
        </p>
      </div>

      <BaseDrawer
        :open="drawerOpen"
        :title="t('issues.create', '新建 Issue')"
        @close="drawerOpen = false"
      >
        <form class="issue-form" @submit.prevent="submitCreate">
          <label>
            {{ t('issues.titleLabel', '标题') }}
            <input v-model="form.title" name="title" maxlength="120" required />
          </label>
          <label>
            {{ t('issues.descriptionLabel', '需求描述') }}
            <textarea
              v-model="form.description"
              name="description"
              rows="5"
              maxlength="4000"
              required
            />
          </label>
          <label>
            {{ t('issues.labelsLabel', '标签') }}
            <input
              v-model="form.labels"
              name="labels"
              maxlength="200"
              :placeholder="t('issues.labelsPlaceholder', '用逗号或空格分隔，可不填')"
            />
          </label>
          <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
          <div class="drawer-actions">
            <BaseButton type="submit" variant="primary" :disabled="creating">
              {{ creating ? t('common.creating', '创建中…') : t('common.create', '创建') }}
            </BaseButton>
            <BaseButton variant="ghost" @click="drawerOpen = false">{{
              t('common.cancel', '取消')
            }}</BaseButton>
          </div>
        </form>
      </BaseDrawer>
    </template>
  </section>
</template>

<style scoped>
.issues-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ff-space-3);
  flex-wrap: wrap;
}
.issues-actions {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
}
.filter-label {
  display: flex;
  align-items: center;
  gap: var(--ff-space-1);
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.issues-layout {
  /* P30：需求列表右移（左侧已有全局导航，与整合页右侧栏口径一致）——
     DOM 序不变，网格列交换 + direction 反转让列表落右、详情落左 */
  display: grid;
  grid-template-columns: 1fr minmax(16rem, 22rem);
  direction: rtl;
  gap: var(--ff-space-4);
  align-items: start;
}
.issues-layout > * {
  direction: ltr;
}
.issue-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  max-height: 70vh;
  overflow: auto;
}
.issue-item {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
  padding: var(--ff-space-2) var(--ff-space-3);
  text-align: left;
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  cursor: pointer;
}
.issue-item.active {
  border-color: var(--ff-primary);
  box-shadow: 0 0 0 1px var(--ff-primary);
}
.issue-item-title {
  font-weight: 600;
}
.issue-item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ff-space-2);
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
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
.issue-empty-hint {
  color: var(--ff-text-muted);
  padding: var(--ff-space-4);
}
.issue-form label {
  display: block;
  margin-bottom: var(--ff-space-3);
}
.issue-form input,
.issue-form textarea {
  display: block;
  width: 100%;
  margin-top: var(--ff-space-1);
  padding: var(--ff-space-2);
  box-sizing: border-box;
}
.drawer-actions {
  display: flex;
  gap: var(--ff-space-2);
  margin-top: var(--ff-space-3);
}
@media (max-width: 60rem) {
  .issues-layout {
    grid-template-columns: 1fr;
  }
}
</style>
