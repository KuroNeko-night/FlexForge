<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { queryAuditEvents, type AuditEventRecord } from '@/api/system';
import ListPager from '@/components/ListPager.vue';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 审计日志页（P24，FR-AUTH-04）：消费 P03 查询 API——操作者/动作/对象过滤 +
 * 分页浏览。仅 ADMIN（菜单可见性为体验层，服务端 @RequireRole 为边界）；
 * 时间窗过滤 UI 登记为候选（docs/09 P24 非目标）。
 */
const PAGE_SIZE = 20;

const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);
const events = ref<AuditEventRecord[]>([]);
const total = ref(0);
const page = ref(1);
const filters = reactive({ actor: '', action: '', objectId: '' });
const applied = reactive({ actor: '', action: '', objectId: '' });

function appliedParams(): { actor?: string; action?: string; objectId?: string } {
  return {
    actor: applied.actor.trim() || undefined,
    action: applied.action.trim() || undefined,
    objectId: applied.objectId.trim() || undefined,
  };
}

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    const result = await queryAuditEvents({
      page: page.value,
      pageSize: PAGE_SIZE,
      ...appliedParams(),
    });
    events.value = result.items;
    total.value = result.total;
    state.value = result.items.length === 0 && page.value === 1 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

function applyFilters(): void {
  page.value = 1;
  Object.assign(applied, { ...filters });
  void load();
}

function resetFilters(): void {
  Object.assign(filters, { actor: '', action: '', objectId: '' });
  applyFilters();
}

/** 本地时区呈现（审计存储 UTC；表格仅展示，不做时区换算配置）。 */
function formatTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

onMounted(load);
</script>

<template>
  <section class="audit-view" data-testid="audit-view">
    <header class="audit-header">
      <h2>{{ t('audit.title', '审计日志') }}</h2>
      <BaseButton @click="load">{{ t('common.refresh', '刷新') }}</BaseButton>
    </header>

    <form class="audit-filters" data-testid="audit-filters" @submit.prevent="applyFilters">
      <label>
        操作者
        <input v-model="filters.actor" name="actor" data-testid="audit-filter-actor" />
      </label>
      <label>
        动作
        <input v-model="filters.action" name="action" data-testid="audit-filter-action" />
      </label>
      <label>
        对象
        <input v-model="filters.objectId" name="objectId" data-testid="audit-filter-object" />
      </label>
      <div class="filter-actions">
        <BaseButton type="submit" variant="primary">{{ t('common.search', '查询') }}</BaseButton>
        <BaseButton variant="ghost" @click="resetFilters">重置</BaseButton>
      </div>
    </form>

    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">当前条件下没有审计记录</p>
    </StateView>
    <table v-else class="audit-table" data-testid="audit-table">
      <thead>
        <tr>
          <th scope="col">时间</th>
          <th scope="col">操作者</th>
          <th scope="col">动作</th>
          <th scope="col">对象</th>
          <th scope="col">结果</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="event in events" :key="event.id">
          <td class="cell-time">{{ formatTime(event.occurredAt) }}</td>
          <td>{{ event.actor }}</td>
          <td>
            <code>{{ event.action }}</code>
          </td>
          <td class="cell-object">{{ event.objectId }}</td>
          <td>
            <span class="result-chip" :data-result="event.result">{{ event.result }}</span>
          </td>
        </tr>
      </tbody>
    </table>
    <ListPager
      v-if="state === 'ready'"
      :page="page"
      :total="total"
      :page-size="PAGE_SIZE"
      @prev="
        page--;
        load();
      "
      @next="
        page++;
        load();
      "
    />
  </section>
</template>

<style scoped>
.audit-view {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.audit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.audit-filters {
  display: flex;
  align-items: flex-end;
  gap: var(--ff-space-3);
  flex-wrap: wrap;
}
.audit-filters label {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.audit-filters input {
  min-width: 10rem;
  padding: var(--ff-space-2);
  border-radius: var(--ff-radius-md);
}
.filter-actions {
  display: flex;
  gap: var(--ff-space-2);
}
.audit-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  overflow: hidden;
}
.audit-table th,
.audit-table td {
  padding: var(--ff-space-2) var(--ff-space-3);
  border-bottom: 1px solid var(--ff-border-soft);
  text-align: left;
}
.audit-table thead th {
  font-size: var(--ff-text-sm);
  font-weight: 500;
  color: var(--ff-text-muted);
  border-bottom: 1px solid var(--ff-border);
}
.audit-table tbody tr:last-child td {
  border-bottom: none;
}
.audit-table code {
  font-family: var(--ff-font-mono);
  font-size: var(--ff-text-sm);
}
.cell-time {
  white-space: nowrap;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.cell-object {
  font-family: var(--ff-font-mono);
  font-size: var(--ff-text-sm);
}
.result-chip {
  padding: 0 var(--ff-space-2);
  border-radius: 999px;
  font-size: var(--ff-text-xs, 0.75rem);
  background: var(--ff-surface-muted);
  color: var(--ff-text-muted);
}
.result-chip[data-result='success'] {
  background: color-mix(in srgb, var(--ff-primary) 14%, transparent);
  color: var(--ff-primary);
}
.result-chip[data-result='failure'] {
  background: var(--ff-danger-bg);
  color: var(--ff-danger);
}
</style>
