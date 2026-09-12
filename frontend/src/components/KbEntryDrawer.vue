<script setup lang="ts">
import { ref, watch } from 'vue';

import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';
import type { KbEntry } from '@/api/kb';
import { t } from '@/registry/localeRegistry';

/**
 * 知识条目新建/编辑抽屉（P28，QG-4 行数从 KnowledgeView 拆出）：表单状态自持，
 * 提交以 save 事件上抛（API 与刷新在父层）；编辑态打开时回填既有条目。
 */
const props = defineProps<{
  open: boolean;
  entry: KbEntry | null;
  saving: boolean;
  formError: string | null;
}>();
const emit = defineEmits<{ close: []; save: [payload: EntryPayload] }>();

export interface EntryPayload {
  title: string;
  category: string | null;
  content: string;
}

const TITLE_MAX = 120;
const CATEGORY_MAX = 40;
const CONTENT_MAX = 20000;

const title = ref('');
const category = ref('');
const content = ref('');

watch(
  () => props.open,
  (open) => {
    if (open) {
      title.value = props.entry?.title ?? '';
      category.value = props.entry?.category ?? '';
      content.value = props.entry?.content ?? '';
    }
  },
);

function submit(): void {
  if (title.value.trim() === '' || content.value.trim() === '') {
    return;
  }
  emit('save', {
    title: title.value,
    category: category.value.trim() === '' ? null : category.value,
    content: content.value,
  });
}
</script>

<template>
  <BaseDrawer
    :open="open"
    :title="entry ? t('kb.edit', '编辑条目') : t('kb.create', '新建条目')"
    @close="emit('close')"
  >
    <form class="entry-form" data-testid="kb-entry-form" @submit.prevent="submit">
      <label>
        {{ t('kb.fieldTitle', '标题') }}
        <input
          v-model="title"
          name="title"
          :maxlength="TITLE_MAX"
          required
          data-testid="kb-entry-title"
        />
      </label>
      <label>
        {{ t('kb.fieldCategory', '分类') }}
        <input
          v-model="category"
          name="category"
          :maxlength="CATEGORY_MAX"
          :placeholder="t('kb.categoryPlaceholder', '可选，如 财务制度 / 使用教程')"
          data-testid="kb-entry-category"
        />
      </label>
      <label>
        {{ t('kb.fieldContent', '内容') }}
        <textarea
          v-model="content"
          name="content"
          rows="12"
          :maxlength="CONTENT_MAX"
          required
          data-testid="kb-entry-content"
        />
        <span class="char-hint">
          {{ t('kb.charCount', '当前') }} {{ content.length }} / {{ CONTENT_MAX }}
        </span>
      </label>
      <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
      <div class="drawer-actions">
        <BaseButton type="submit" variant="primary" :disabled="saving" data-testid="kb-entry-save">
          {{ saving ? t('common.saving', '保存中…') : t('common.save', '保存') }}
        </BaseButton>
        <BaseButton variant="ghost" @click="emit('close')">
          {{ t('common.cancel', '取消') }}
        </BaseButton>
      </div>
    </form>
  </BaseDrawer>
</template>

<style scoped>
.entry-form :deep(label) {
  display: block;
  margin-bottom: var(--ff-space-3);
}
.entry-form textarea {
  width: 100%;
  resize: vertical;
}
.char-hint {
  display: block;
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
  margin-top: var(--ff-space-1);
}
.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ff-space-2);
}
</style>
