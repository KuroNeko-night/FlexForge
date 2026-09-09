<script setup lang="ts">
import { computed } from 'vue';

import { ISSUE_STATUS_LABELS, type IssueRecord } from '@/api/issues';
import AppIcon from '@/components/AppIcon.vue';
import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 用户端工作台侧栏（P23 FR-ISSUE-07）：我的需求列表——已发布分组置顶，
 * 梳理中随后；可折叠。纯展示组件（数据与选中态由父层持有）。
 */
const props = defineProps<{
  issues: IssueRecord[];
  activeId: string | null;
  collapsed: boolean;
  loading: boolean;
  error: string | null;
}>();
defineEmits<{ toggle: []; create: []; select: [issue: IssueRecord] }>();

const published = computed(() => props.issues.filter((issue) => issue.publishedAt));
const drafts = computed(() => props.issues.filter((issue) => !issue.publishedAt));
</script>

<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="sidebar-head">
      <h3 v-if="!collapsed">我的需求</h3>
      <button
        type="button"
        class="collapse-toggle"
        :data-testid="collapsed ? 'expand-sidebar' : 'collapse-sidebar'"
        :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
        @click="$emit('toggle')"
      >
        <AppIcon :name="collapsed ? 'chevron-right' : 'chevron-left'" :size="16" />
      </button>
    </div>
    <template v-if="!collapsed">
      <BaseButton
        variant="primary"
        class="new-button"
        data-testid="new-requirement"
        @click="$emit('create')"
      >
        新建需求
      </BaseButton>
      <div v-if="loading" class="sidebar-hint">加载中…</div>
      <div v-else-if="error" class="sidebar-hint">{{ error }}</div>
      <nav v-else class="sidebar-list" data-testid="my-issues">
        <p v-if="published.length > 0" class="group-label">已发布</p>
        <button
          v-for="issue in published"
          :key="issue.id"
          type="button"
          class="issue-entry"
          :class="{ active: activeId === issue.id }"
          :data-testid="`issue-entry-${issue.id}`"
          @click="$emit('select', issue)"
        >
          <span class="entry-title">{{ issue.title }}</span>
          <span class="entry-meta">{{ ISSUE_STATUS_LABELS[issue.status] }}</span>
        </button>
        <p v-if="drafts.length > 0" class="group-label">梳理中</p>
        <button
          v-for="issue in drafts"
          :key="issue.id"
          type="button"
          class="issue-entry"
          :class="{ active: activeId === issue.id }"
          @click="$emit('select', issue)"
        >
          <span class="entry-title">{{ issue.title }}</span>
          <span class="entry-meta">{{ ISSUE_STATUS_LABELS[issue.status] }}</span>
        </button>
        <p v-if="issues.length === 0" class="sidebar-hint">还没有需求，先新建一个</p>
      </nav>
    </template>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 14rem;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  padding: var(--ff-space-3);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface);
  transition: width var(--ff-motion-base) ease;
}
.sidebar.collapsed {
  width: 3rem;
  align-items: center;
}
.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.sidebar-head h3 {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.collapse-toggle {
  border: none;
  background: none;
  cursor: pointer;
  color: var(--ff-text-muted);
  padding: var(--ff-space-1);
  border-radius: var(--ff-radius-sm);
}
.collapse-toggle:hover {
  background: var(--ff-surface-muted);
}
.new-button {
  width: 100%;
}
.sidebar-hint,
.group-label {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.group-label {
  margin-top: var(--ff-space-1);
}
.sidebar-list {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
  overflow-y: auto;
}
.issue-entry {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: left;
  padding: var(--ff-space-2);
  border: 1px solid transparent;
  border-radius: var(--ff-radius-md);
  background: none;
  cursor: pointer;
}
.issue-entry:hover {
  background: var(--ff-surface-muted);
}
.issue-entry.active {
  border-color: color-mix(in srgb, var(--ff-primary) 40%, transparent);
  background: var(--ff-primary-soft);
}
.entry-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry-meta {
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
}
</style>
