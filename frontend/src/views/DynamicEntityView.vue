<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { createRecord, deleteRecord, fetchRecord, queryRecords, updateRecord } from '@/api/data';
import { ApiError } from '@/api/client';
import type { RecordView, ViewDefinition } from '@/api/types';
import DynamicForm from '@/components/DynamicForm.vue';
import DynamicTable from '@/components/DynamicTable.vue';
import StateView from '@/components/StateView.vue';
import { useEntityMetadata } from '@/composables/useEntityMetadata';

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
  errorDetail.value =
    e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : String(e);
}

async function loadRecords(): Promise<void> {
  const result = await queryRecords(entityName.value, {
    page: String(page.value),
    pageSize: String(pageSize),
  });
  records.value = result.items;
  total.value = result.total;
}

async function loadRecord(id: string): Promise<void> {
  currentRecord.value = await fetchRecord(entityName.value, id);
}

async function refresh(): Promise<void> {
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
    state.value = 'ready';
  } catch (e) {
    fail(e);
  }
}

async function onSubmit(values: Record<string, unknown>): Promise<void> {
  submitting.value = true;
  formError.value = null;
  try {
    if (mode.value === 'new') {
      const created = await createRecord(entityName.value, values);
      await load(entityName.value);
      page.value = 1;
      await loadRecords();
      state.value = 'ready';
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

async function remove(record: RecordView): Promise<void> {
  if (!window.confirm(`确认删除该记录？`)) {
    return;
  }
  try {
    await deleteRecord(entityName.value, record.id);
    await refresh();
  } catch (e) {
    fail(e);
  }
}

async function openDetail(id: string): Promise<void> {
  await router.push({ name: 'entity-detail', params: { entity: entityName.value, id } });
}

watch(
  () => [route.params.entity, route.params.id, route.name],
  () => {
    if (route.params.entity) {
      page.value = 1;
      formError.value = null;
      void refresh();
    }
  },
);
onMounted(refresh);
</script>

<template>
  <article class="entity-view" :data-entity="entityName" :data-mode="mode">
    <header>
      <h2>{{ definition?.displayName ?? entityName }}</h2>
      <p v-if="state === 'stale'" class="stale-note">元数据已更新，数据已按新版本重新加载</p>
    </header>

    <StateView v-if="state === 'loading' || state === 'denied'" :state="state" />
    <StateView v-else-if="state === 'error'" :state="'error'" :detail="errorDetail">
      <button type="button" @click="refresh">重试</button>
    </StateView>

    <template v-else-if="mode === 'list'">
      <StateView v-if="records.length === 0" :state="'empty'">
        <button type="button" @click="refresh">刷新</button>
      </StateView>
      <DynamicTable
        v-else
        :definition="definition!"
        :view="listView"
        :records="records"
        @row-click="(record) => openDetail(record.id)"
      >
        <template #actions="{ record }">
          <button type="button" @click.stop="openDetail(record.id)">详情</button>
          <button
            type="button"
            @click.stop="router.push({ name: 'entity-edit', params: { entity: entityName, id: record.id } })"
          >
            编辑
          </button>
          <button type="button" @click.stop="remove(record)">删除</button>
        </template>
      </DynamicTable>
      <footer class="pager">
        <button type="button" :disabled="page <= 1" @click="page--; refresh()">上一页</button>
        <span>第 {{ page }} 页 / 共 {{ Math.max(1, Math.ceil(total / pageSize)) }} 页（{{ total }} 条）</span>
        <button
          type="button"
          :disabled="page >= Math.ceil(total / pageSize)"
          @click="page++; refresh()"
        >
          下一页
        </button>
      </footer>
      <router-link class="create-link" :to="`/data/${entityName}/new`">新增记录</router-link>
    </template>

    <StateView v-else-if="mode === 'detail' && !currentRecord" :state="'loading'" />
    <dl v-else-if="mode === 'detail'" class="detail-list">
      <template v-for="field in definition?.fields ?? []" :key="field.id">
        <dt>{{ field.displayName }}</dt>
        <dd :data-field="field.name">{{ currentRecord?.data[field.name] ?? '—' }}</dd>
      </template>
      <div class="detail-actions">
        <router-link :to="`/data/${entityName}/${currentRecord?.id}/edit`">编辑</router-link>
        <button type="button" @click="currentRecord && remove(currentRecord)">删除</button>
        <router-link :to="`/data/${entityName}`">返回列表</router-link>
      </div>
    </dl>

    <DynamicForm
      v-else
      :definition="definition!"
      :view="formView"
      :initial="mode === 'edit' ? currentRecord?.data : null"
      :submit-label="mode === 'new' ? '创建' : '保存'"
      :submitting="submitting"
      @submit="onSubmit"
    />
    <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
  </article>
</template>
