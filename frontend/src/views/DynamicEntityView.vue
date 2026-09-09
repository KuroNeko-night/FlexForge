<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { createRecord, fetchRecord, queryRecords, updateRecord } from '@/api/data';
import { ApiError, apiErrorMessage } from '@/api/client';
import type { RecordView, ViewDefinition } from '@/api/types';
import { buildCsv, csvSafeFilename, downloadCsv } from '@/utils/csv';
import { visibleColumns } from '@/utils/viewColumns';
import DynamicForm from '@/components/DynamicForm.vue';
import DynamicTable from '@/components/DynamicTable.vue';
import EntityDetailSection from '@/components/EntityDetailSection.vue';
import KanbanView from '@/components/KanbanView.vue';
import ListPager from '@/components/ListPager.vue';
import ProcessorDrawer from '@/components/ProcessorDrawer.vue';
import RecordActionsBar from '@/components/RecordActionsBar.vue';
import ViewToggle from '@/components/ViewToggle.vue';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import { useEntityMetadata } from '@/composables/useEntityMetadata';
import { useConfirmAction } from '@/composables/useConfirmAction';
import { useEntityProcessors } from '@/composables/useEntityProcessors';
import { useKanbanMove } from '@/composables/useKanbanMove';
import { visibleRecordActions, type ActionContext } from '@/registry/recordActionRegistry';

/** 动态实体页（docs/09 P06 验收 1）：四模式由路由参数驱动，无业务页面代码；P17 增看板呈现。 */
const route = useRoute();
const router = useRouter();
const { definition, versionChanged, load } = useEntityMetadata();

const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'stale'>('loading');
const errorDetail = ref<string | null>(null);
const records = ref<RecordView[]>([]);
const total = ref(0);
const page = ref(1);
const currentRecord = ref<RecordView | null>(null);
const submitting = ref(false);
const formError = ref<string | null>(null);

const entityName = computed(() => String(route.params.entity ?? ''));
const mode = computed<'list' | 'detail' | 'new' | 'edit'>(() => {
  // 静态段路由 data/:entity/new 不会产出 params.id（vue-router 仅参数段进 params），
  // 必须按路由名判定；params.id === 'new' 分支保留兼容既有 mock 路由的测试
  if (route.name === 'entity-new' || route.params.id === 'new') {
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
const kanbanView = computed<ViewDefinition | null>(
  () => definition.value?.views.find((view) => view.viewType === 'kanban') ?? null,
);
/** 列表页呈现切换（P17）：实体声明了 kanban 视图即可在看板/表格间切换。 */
const presentation = ref<'table' | 'kanban'>('table');
const pageSize = computed(() => (presentation.value === 'kanban' ? 100 : 20));

function fail(e: unknown): void {
  if (e instanceof ApiError && e.status === 403) {
    state.value = 'denied';
    return;
  }
  state.value = 'error';
  errorDetail.value = apiErrorMessage(e, String(e));
}

async function loadRecords(seq: number): Promise<void> {
  const result = await queryRecords(entityName.value, {
    page: String(page.value),
    pageSize: String(pageSize.value),
  });
  // 末页删光后回退到新的最后一页（仅校正一次：已在末页仍空则接受快照偏差，防递归）
  const lastPage = Math.max(1, Math.ceil(result.total / pageSize.value));
  if (result.items.length === 0 && result.total > 0 && page.value !== lastPage && page.value > 1) {
    page.value = lastPage;
    await loadRecords(seq);
    return;
  }
  if (seq === refreshSeq) {
    // 迟到旧响应不覆盖新页（审查 P3-3）
    records.value = result.items;
    total.value = result.total;
  }
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
      await loadRecords(seq);
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

/** 列表动作统一确认（P16 模式，P19 抽 useConfirmAction；详情删除已拆 EntityDetailSection）。 */
const { confirmState, confirmAction, settleConfirm } = useConfirmAction();

async function openDetail(id: string): Promise<void> {
  await router.push({ name: 'entity-detail', params: { entity: entityName.value, id } });
}

/** 呈现切换（P17）：换页大小后整页重拉。 */
async function switchPresentation(next: 'table' | 'kanban'): Promise<void> {
  if (presentation.value === next) {
    return;
  }
  presentation.value = next;
  page.value = 1;
  await refresh();
}

/** 看板拖拽换列编排（P19，P20 抽 useKanbanMove）/ 处理器分析入口（P20）。 */
const { moveError, onCardMove } = useKanbanMove(entityName, kanbanView, records);
const { available: hasProcessors, drawerOpen: analysisOpen } = useEntityProcessors(entityName);

/** 导出 CSV（P19）：当前已加载记录与可见列的本地生成（无网络请求）。 */
function exportCsv(): void {
  const columns = visibleColumns(definition.value?.fields ?? [], listView.value);
  if (columns.length === 0) {
    return;
  }
  const headers = columns.map((field) => field.displayName);
  const rows = records.value.map((record) =>
    columns.map((field) => record.data[field.name] ?? null),
  );
  const name = csvSafeFilename(definition.value?.displayName ?? entityName.value);
  downloadCsv(`${name}-导出.csv`, buildCsv(headers, rows));
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
    // 仅切换实体时重置分页与呈现；同实体内详情/编辑/翻页保留位置
    if (route.params.entity !== watchedEntity) {
      watchedEntity = String(route.params.entity);
      page.value = 1;
      presentation.value = 'table';
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
      <div v-if="state === 'ready' && mode === 'list'" class="header-actions">
        <ViewToggle v-if="kanbanView" :presentation="presentation" @change="switchPresentation" />
        <BaseButton v-if="hasProcessors" data-testid="open-analysis" @click="analysisOpen = true">
          数据分析
        </BaseButton>
        <BaseButton data-testid="export-csv" @click="exportCsv">导出 CSV</BaseButton>
        <BaseButton variant="primary" @click="router.push(`/data/${entityName}/new`)">
          新增记录
        </BaseButton>
      </div>
      <p v-if="state === 'ready' && versionChanged" class="stale-note">
        元数据已更新，数据已按新版本重新加载
      </p>
    </header>

    <StateView v-if="state === 'loading' || state === 'denied'" :state="state" />
    <StateView v-else-if="state === 'error'" :state="'error'" :detail="errorDetail">
      <button type="button" @click="refresh">重试</button>
    </StateView>

    <!-- P19 呈现切换过渡：表格↔看板 out-in 轻位移；模式分支保持 v-else-if 链 -->
    <Transition v-else-if="mode === 'list'" name="presentation-swap" mode="out-in">
      <div v-if="presentation === 'kanban' && kanbanView" key="kanban" class="presentation-block">
        <KanbanView
          :definition="definition!"
          :view="kanbanView"
          :records="records"
          :total="total"
          @card-click="(record) => openDetail(record.id)"
          @card-move="onCardMove"
        />
        <p v-if="moveError" class="form-error" role="alert">{{ moveError }}</p>
      </div>
      <div v-else key="table" class="presentation-block">
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
            <RecordActionsBar :actions="recordActions" :record="record" @run="runAction" />
          </template>
        </DynamicTable>
        <ListPager
          :page="page"
          :total="total"
          :page-size="pageSize"
          @prev="
            page--;
            refresh();
          "
          @next="
            page++;
            refresh();
          "
        />
      </div>
    </Transition>

    <StateView v-else-if="mode === 'detail' && !currentRecord" :state="'loading'" />
    <EntityDetailSection
      v-else-if="mode === 'detail'"
      :definition="definition!"
      :record="currentRecord"
      @error="fail"
    />

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

    <ProcessorDrawer :open="analysisOpen" :entity="entityName" @close="analysisOpen = false" />
  </article>
</template>
