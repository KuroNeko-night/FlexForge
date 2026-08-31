<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { createRecord, deleteRecord, fetchRecord, queryRecords, updateRecord } from '@/api/data';
import { ApiError, apiErrorMessage } from '@/api/client';
import type { RecordView, ViewDefinition } from '@/api/types';
import DynamicForm from '@/components/DynamicForm.vue';
import DynamicTable from '@/components/DynamicTable.vue';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import { useEntityMetadata } from '@/composables/useEntityMetadata';
import { visibleRecordActions, type ActionContext } from '@/registry/recordActionRegistry';

/**
 * 动态实体页（docs/09 P06 验收 1）：列表/详情/新建/编辑四模式由路由参数驱动，
 * 无任何业务页面代码。五状态：loading/empty/error/denied/stale——stale 在元数据
 * 版本递增时出现并自动重载数据；403 → denied；错误附 requestId。
 */
const route = useRoute();
const router = useRouter();
const { definition, versionChanged, load } = useEntityMetadata();

const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'stale'>('loading');
const errorDetail = ref<string | null>(null);
const records = ref<RecordView[]>([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const currentRecord = ref<RecordView | null>(null);
const submitting = ref(false);
const formError = ref<string | null>(null);

const entityName = computed(() => String(route.params.entity ?? ''));
const mode = computed<'list' | 'detail' | 'new' | 'edit'>(() => {
  if (route.params.id === 'new') {
    return 'new';
  }
  if (route.name === 'entity-edit') {
    return 'edit';
  }
  return route.params.id ? 'detail' : 'list';
});
const listView = computed<ViewDefinition | null>(
  () => definition.value?.views.find((view) => view.viewType === 'list') ?? null,
);
const formView = computed<ViewDefinition | null>(
  () => definition.value?.views.find((view) => view.viewType === 'form') ?? null,
);

function fail(e: unknown): void {
  if (e instanceof ApiError && e.status === 403) {
    state.value = 'denied';
    return;
  }
  state.value = 'error';
  errorDetail.value = apiErrorMessage(e, String(e));
}

async function loadRecords(): Promise<void> {
  const result = await queryRecords(entityName.value, {
    page: String(page.value),
    pageSize: String(pageSize),
  });
  // 末页删光后回退到新的最后一页（仅校正一次：已在末页仍空则接受快照偏差，防递归）
  const lastPage = Math.max(1, Math.ceil(result.total / pageSize));
  if (result.items.length === 0 && result.total > 0 && page.value !== lastPage && page.value > 1) {
    page.value = lastPage;
    await loadRecords();
    return;
  }
  records.value = result.items;
  total.value = result.total;
}

async function loadRecord(id: string): Promise<void> {
  currentRecord.value = await fetchRecord(entityName.value, id);
}

let refreshSeq = 0;

async function refresh(): Promise<void> {
  // 竞态守卫：仅最后一次触发可落盘状态（连点分页/快速导航的慢响应覆盖）
  const seq = ++refreshSeq;
  state.value = 'loading';
  errorDetail.value = null;
  try {
    await load(entityName.value);
    if (versionChanged.value) {
      state.value = 'stale';
    }
    if (mode.value === 'list') {
      await loadRecords();
    } else if (mode.value === 'new') {
      currentRecord.value = null;
    } else if (route.params.id) {
      await loadRecord(String(route.params.id));
    }
    if (seq === refreshSeq) {
      state.value = 'ready';
    }
  } catch (e) {
    if (seq === refreshSeq) {
      fail(e);
    }
  }
}

async function onSubmit(values: Record<string, unknown>): Promise<void> {
  submitting.value = true;
  formError.value = null;
  try {
    if (mode.value === 'new') {
      // 创建成功直接跳详情；数据重载统一由路由 watch → refresh（seq 守卫）接管
      const created = await createRecord(entityName.value, values);
      await openDetail(created.id);
      return;
    }
    if (currentRecord.value) {
      await updateRecord(entityName.value, currentRecord.value.id, values);
      await refresh();
    }
  } catch (e) {
    formError.value = e instanceof ApiError ? e.message : '提交失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

/** 详情/编辑页删除与列表动作共用统一确认对话框（P16）：registry 动作经 context.confirm。 */
const confirmState = ref<{
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  danger: boolean;
} | null>(null);
let confirmResolver: ((ok: boolean) => void) | null = null;

function confirmAction(
  message: string,
  options?: { title?: string; confirmLabel?: string; danger?: boolean },
): Promise<boolean> {
  confirmState.value = {
    open: true,
    title: options?.title ?? '确认操作',
    message,
    confirmLabel: options?.confirmLabel ?? '确认',
    danger: options?.danger ?? false,
  };
  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}

function settleConfirm(ok: boolean): void {
  confirmResolver?.(ok);
  confirmResolver = null;
  confirmState.value = null;
}

async function removeFromDetail(record: RecordView): Promise<void> {
  if (
    !(await confirmAction('删除后不可恢复，确认删除该记录？', {
      title: '删除记录',
      confirmLabel: '删除',
      danger: true,
    }))
  ) {
    return;
  }
  try {
    await deleteRecord(entityName.value, record.id);
    await router.push({ name: 'entity-list', params: { entity: entityName.value } });
  } catch (e) {
    fail(e);
  }
}

async function openDetail(id: string): Promise<void> {
  await router.push({ name: 'entity-detail', params: { entity: entityName.value, id } });
}

async function editRecord(id: string): Promise<void> {
  await router.push({ name: 'entity-edit', params: { entity: entityName.value, id } });
}

// 表格动作栏 = 内置动作 + registry 贡献（extension.record-action 消费面）
const recordActions = visibleRecordActions();
const actionContext = computed<ActionContext>(() => ({
  entity: entityName.value,
  openDetail,
  edit: editRecord,
  refresh,
  confirm: confirmAction,
}));

async function runAction(actionKey: string, record: RecordView): Promise<void> {
  const action = recordActions.value.find((item) => item.key === actionKey);
  if (action) {
    try {
      await action.handler(record, actionContext.value);
    } catch (e) {
      fail(e);
    }
  }
}

// 挂载时即记录当前实体：首次同实体内导航（列表→详情）不重置分页
let watchedEntity = String(route.params.entity ?? '');
watch(
  () => [route.params.entity, route.params.id, route.name],
  () => {
    if (!route.params.entity) {
      return;
    }
    // 仅切换实体时重置分页；同实体内详情/编辑/翻页保留位置
    if (route.params.entity !== watchedEntity) {
      watchedEntity = String(route.params.entity);
      page.value = 1;
    }
    formError.value = null;
    void refresh();
  },
);
onMounted(refresh);
</script>

<template>
  <article class="entity-view" :data-entity="entityName" :data-mode="mode">
    <header>
      <h2>{{ definition?.displayName ?? entityName }}</h2>
      <BaseButton
        v-if="state === 'ready' && mode === 'list'"
        variant="primary"
        class="header-create"
        @click="router.push(`/data/${entityName}/new`)"
      >
        新增记录
      </BaseButton>
      <p v-if="state === 'ready' && versionChanged" class="stale-note">
        元数据已更新，数据已按新版本重新加载
      </p>
    </header>

    <StateView v-if="state === 'loading' || state === 'denied'" :state="state" />
    <StateView v-else-if="state === 'error'" :state="'error'" :detail="errorDetail">
      <button type="button" @click="refresh">重试</button>
    </StateView>

    <template v-else-if="mode === 'list'">
      <StateView v-if="records.length === 0" :state="'empty'">
        <BaseButton variant="primary" @click="router.push(`/data/${entityName}/new`)">
          新增记录
        </BaseButton>
      </StateView>
      <DynamicTable
        v-else
        :definition="definition!"
        :view="listView"
        :records="records"
        @row-click="(record) => openDetail(record.id)"
      >
        <template #actions="{ record }">
          <button
            v-for="action in recordActions"
            :key="action.key"
            type="button"
            :data-action="action.key"
            @click.stop="runAction(action.key, record)"
          >
            {{ action.label }}
          </button>
        </template>
      </DynamicTable>
      <footer class="pager">
        <button
          type="button"
          :disabled="page <= 1"
          @click="
            page--;
            refresh();
          "
        >
          上一页
        </button>
        <span
          >第 {{ page }} / {{ Math.max(1, Math.ceil(total / pageSize)) }} 页 · 共
          {{ total }} 条</span
        >
        <button
          type="button"
          :disabled="page >= Math.ceil(total / pageSize)"
          @click="
            page++;
            refresh();
          "
        >
          下一页
        </button>
      </footer>
    </template>

    <StateView v-else-if="mode === 'detail' && !currentRecord" :state="'loading'" />
    <dl v-else-if="mode === 'detail'" class="detail-list">
      <template v-for="field in definition?.fields ?? []" :key="field.id">
        <dt>{{ field.displayName }}</dt>
        <dd :data-field="field.name">{{ currentRecord?.data[field.name] ?? '—' }}</dd>
      </template>
      <div class="detail-actions">
        <router-link :to="`/data/${entityName}/${currentRecord?.id}/edit`">编辑</router-link>
        <button type="button" @click="currentRecord && removeFromDetail(currentRecord)">
          删除
        </button>
        <router-link :to="`/data/${entityName}`">返回列表</router-link>
      </div>
    </dl>

    <!-- :key 绑定实体+记录 ID：DynamicForm 的 values 是 setup 快照，防跨记录复用残留 -->
    <DynamicForm
      v-else
      :key="`${entityName}-${String(route.params.id ?? 'new')}`"
      :definition="definition!"
      :view="formView"
      :initial="mode === 'edit' ? currentRecord?.data : null"
      :submit-label="mode === 'new' ? '创建' : '保存'"
      :submitting="submitting"
      @submit="onSubmit"
    />
    <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>

    <ConfirmDialog
      :open="confirmState?.open ?? false"
      :title="confirmState?.title ?? ''"
      :message="confirmState?.message ?? ''"
      :confirm-label="confirmState?.confirmLabel ?? '确认'"
      :danger="confirmState?.danger ?? false"
      @confirm="settleConfirm(true)"
      @cancel="settleConfirm(false)"
    />
  </article>
</template>
