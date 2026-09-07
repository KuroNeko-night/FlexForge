<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import {
  fetchComments,
  fetchSpec,
  ISSUE_STATUS_LABELS,
  type IssueComment,
  type IssueRecord,
  type SpecRevision,
} from '@/api/issues';
import { session } from '@/auth/token';
import IssueClarifyChat from '@/components/IssueClarifyChat.vue';
import IssueComments from '@/components/IssueComments.vue';
import IssueDevPanel from '@/components/IssueDevPanel.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * Issue 详情（P15，P21 对话拆分）：AI 对话（clarify 多轮问答，FR-ISSUE-03/03A）
 * 在 IssueClarifyChat（:key=issue.id 切换即复位）；评论区 + 规格状态展示；
 * 开发者面板（迁移/规格编辑/预览/生成）由 IssueDevPanel 承载。clarify 权限=
 * 作者或开发者（后端同口径）；角色仅控制入口显隐，安全边界在服务端。
 */
const props = defineProps<{ issue: IssueRecord }>();
const emit = defineEmits<{
  updated: [issue: IssueRecord];
  specSaved: [spec: SpecRevision];
}>();

const comments = ref<IssueComment[]>([]);
const spec = ref<SpecRevision | null>(null);

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

watch(
  () => props.issue.id,
  () => {
    void loadDetail();
  },
  { immediate: true },
);

/** 对话产出规格草稿：本地同步 + 透传给父级（列表/详情共用一份规格状态）。 */
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
      <IssueClarifyChat
        :key="issue.id"
        :issue-id="issue.id"
        :can-clarify="canClarify"
        @spec-saved="onSpecSaved"
      />
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
.spec-summary {
  margin: 0;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
</style>
