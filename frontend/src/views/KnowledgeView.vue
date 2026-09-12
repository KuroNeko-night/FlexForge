<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { createKbEntry, deleteKbEntry, listKbEntries, updateKbEntry, type KbEntry } from '@/api/kb';
import { hasRole } from '@/auth/token';
import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import KbEntryDrawer, { type EntryPayload } from '@/components/KbEntryDrawer.vue';
import StateView from '@/components/StateView.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 知识库管理页（FR-KB-01，ADMIN 菜单进入）：条目列表 + 关键词过滤 +
 * 新建/编辑抽屉 + 删除确认。条目登录可读（直访路由呈只读视图）；
 * 写操作服务端限 ADMIN（S2），管理按钮按角色显隐只是体验层。
 */
// 响应式角色（审查 P2-1：硬刷新时子路由 setup 先于父壳 fetchMe 回填 session.user，
// 常量快照会让 ADMIN 暂时看到只读视图；computed 随 reactive session 自动更新）
const isAdmin = computed(() => hasRole('ADMIN'));
const entries = ref<KbEntry[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);
const search = ref('');

const drawerOpen = ref(false);
const editing = ref<KbEntry | null>(null);
const saving = ref(false);
const formError = ref<string | null>(null);

const deleteTarget = ref<KbEntry | null>(null);
const deleting = ref(false);
const deleteError = ref<string | null>(null);

const visibleEntries = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  if (keyword === '') {
    return entries.value;
  }
  return entries.value.filter((entry) =>
    [entry.title, entry.category ?? '', entry.content].join('\n').toLowerCase().includes(keyword),
  );
});

/** 时间戳本地化呈现（ISO 原文可读性差，P28 视觉走查修正）。 */
function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    entries.value = await listKbEntries();
    state.value = entries.value.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

function openCreate(): void {
  editing.value = null;
  formError.value = null;
  drawerOpen.value = true;
}

function openEdit(entry: KbEntry): void {
  editing.value = entry;
  formError.value = null;
  drawerOpen.value = true;
}

async function submitEntry(payload: EntryPayload): Promise<void> {
  if (saving.value) {
    return;
  }
  saving.value = true;
  formError.value = null;
  try {
    if (editing.value) {
      await updateKbEntry(editing.value.id, payload);
    } else {
      await createKbEntry(payload);
    }
    drawerOpen.value = false;
    await load();
  } catch (e) {
    formError.value =
      e instanceof ApiError ? e.message : t('kb.saveFailed', '保存失败，请稍后重试');
  } finally {
    saving.value = false;
  }
}

async function submitDelete(): Promise<void> {
  const target = deleteTarget.value;
  if (!target || deleting.value) {
    return;
  }
  deleting.value = true;
  deleteError.value = null;
  try {
    await deleteKbEntry(target.id);
    deleteTarget.value = null;
    await load();
  } catch (e) {
    deleteError.value =
      e instanceof ApiError ? e.message : t('kb.deleteFailed', '删除失败，请稍后重试');
  } finally {
    deleting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="kb-page" data-testid="kb-page">
    <header class="page-head">
      <h2 class="ff-page-title">{{ t('kb.title', '知识库') }}</h2>
      <p class="page-sub">
        {{ t('kb.subtitle', '企业信息与平台使用知识的唯一来源，AI 助手据此回答') }}
      </p>
      <div class="head-actions">
        <input
          v-model="search"
          type="search"
          class="kb-search"
          :placeholder="t('kb.search', '搜索标题 / 分类 / 内容')"
          data-testid="kb-search"
        />
        <BaseButton v-if="isAdmin" variant="primary" data-testid="kb-create" @click="openCreate">
          {{ t('kb.create', '新建条目') }}
        </BaseButton>
      </div>
    </header>
    <StateView
      v-if="state === 'loading' || state === 'error' || state === 'denied'"
      :state="state"
      :message="error"
    />
    <p v-if="state === 'empty'" class="kb-empty" data-testid="kb-empty">
      {{ t('kb.empty', '知识库暂无条目，点击右上角新建第一条') }}
    </p>
    <p
      v-if="state === 'ready' && visibleEntries.length === 0"
      class="kb-empty"
      data-testid="kb-no-match"
    >
      {{ t('kb.noMatch', '没有匹配的条目') }}
    </p>
    <ul v-if="visibleEntries.length > 0" class="kb-list" data-testid="kb-list">
      <li v-for="entry in visibleEntries" :key="entry.id" class="kb-item" data-testid="kb-item">
        <div class="kb-item-head">
          <strong class="kb-title">{{ entry.title }}</strong>
          <span v-if="entry.category" class="kb-category" data-testid="kb-category">
            {{ entry.category }}
          </span>
        </div>
        <p class="kb-preview">{{ entry.content }}</p>
        <div class="kb-meta">
          <span>{{ entry.createdBy }}</span>
          <span class="kb-updated">{{ formatTime(entry.updatedAt) }}</span>
        </div>
        <div v-if="isAdmin" class="kb-actions">
          <BaseButton variant="ghost" data-testid="kb-edit" @click="openEdit(entry)">
            {{ t('common.edit', '编辑') }}
          </BaseButton>
          <BaseButton variant="ghost" data-testid="kb-delete" @click="deleteTarget = entry">
            {{ t('common.delete', '删除') }}
          </BaseButton>
        </div>
      </li>
    </ul>
    <p v-if="deleteError" class="delete-error" role="alert">{{ deleteError }}</p>

    <KbEntryDrawer
      :open="drawerOpen"
      :entry="editing"
      :saving="saving"
      :form-error="formError"
      @close="drawerOpen = false"
      @save="submitEntry"
    />
    <ConfirmDialog
      :open="deleteTarget !== null"
      :title="t('kb.deleteTitle', '删除条目')"
      :message="`${t('kb.deleteBody', '将删除知识条目')}「${deleteTarget?.title ?? ''}」，${t('kb.deleteTail', 'AI 助手将不再引用它，不可恢复。')}`"
      :confirm-label="t('common.delete', '删除')"
      :danger="true"
      :busy="deleting"
      @confirm="submitDelete"
      @cancel="deleteTarget = null"
    />
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: baseline;
  gap: var(--ff-space-3);
  flex-wrap: wrap;
  margin-bottom: var(--ff-space-3);
}
.page-sub {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
  flex: 1 1 24ch;
}
.head-actions {
  display: flex;
  gap: var(--ff-space-2);
  align-items: center;
}
.kb-search {
  width: 24ch;
}
.kb-empty {
  color: var(--ff-text-muted);
  text-align: center;
  padding: var(--ff-space-4) 0;
}
.kb-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.kb-item {
  background: var(--ff-surface);
  border: 1px solid var(--ff-border);
  border-radius: var(--ff-radius-md);
  padding: var(--ff-space-3);
}
.kb-item-head {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
}
.kb-title {
  font-size: var(--ff-text-md);
}
.kb-category {
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  padding: 1px var(--ff-space-2);
}
.kb-preview {
  margin: var(--ff-space-2) 0;
  color: var(--ff-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-line;
}
.kb-meta {
  display: flex;
  gap: var(--ff-space-3);
  color: var(--ff-text-muted);
  font-size: var(--ff-text-xs, 0.75rem);
}
.kb-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ff-space-2);
  margin-top: var(--ff-space-2);
}
.delete-error {
  color: var(--ff-danger);
  font-size: var(--ff-text-sm);
}
</style>
