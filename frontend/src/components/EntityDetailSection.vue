<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { deleteRecord } from '@/api/data';
import type { EntityDetail, RecordView } from '@/api/types';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';

/**
 * 实体详情段（P17 从 DynamicEntityView 拆出）：字段键值列表 + 编辑/删除/返回
 * 动作；删除经统一确认对话框（P16 口径），成功后回列表（数据重载由父级
 * 路由 watch 驱动，错误上抛父级统一呈现）。
 */
const props = defineProps<{ definition: EntityDetail; record: RecordView | null }>();
const emit = defineEmits<{ error: [e: unknown] }>();

const route = useRoute();
const router = useRouter();
const confirmDelete = ref(false);
const deleting = ref(false);

const entityId = computed(() => String(route.params.entity ?? ''));

async function remove(): Promise<void> {
  if (!props.record || deleting.value) {
    return;
  }
  deleting.value = true;
  try {
    await deleteRecord(entityId.value, props.record.id);
    confirmDelete.value = false;
    await router.push({ name: 'entity-list', params: { entity: entityId.value } });
  } catch (e) {
    confirmDelete.value = false;
    emit('error', e);
  } finally {
    deleting.value = false;
  }
}
</script>

<template>
  <dl class="detail-list">
    <template v-for="field in definition.fields" :key="field.id">
      <dt>{{ field.displayName }}</dt>
      <dd :data-field="field.name">{{ record?.data[field.name] ?? '—' }}</dd>
    </template>
    <div class="detail-actions">
      <router-link :to="`/data/${entityId}/${record?.id}/edit`">编辑</router-link>
      <button type="button" @click="confirmDelete = true">删除</button>
      <router-link :to="`/data/${entityId}`">返回列表</router-link>
    </div>
  </dl>

  <ConfirmDialog
    :open="confirmDelete"
    title="删除记录"
    message="删除后不可恢复，确认删除该记录？"
    confirm-label="删除"
    danger
    :busy="deleting"
    @confirm="remove"
    @cancel="confirmDelete = false"
  />
</template>
