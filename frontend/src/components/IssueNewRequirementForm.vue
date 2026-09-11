<script setup lang="ts">
import { ref } from 'vue';

import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 用户工作台·新需求表单（P26 从 IssueChatWorkbench 拆出，QG-4 行数）：
 * 表单状态自持，create 事件上抛（API/跳转在父层）。
 */
defineProps<{ creating: boolean; createError: string | null }>();
const emit = defineEmits<{
  create: [payload: { title: string; description: string }];
}>();

const form = ref({ title: '', description: '' });

function submit(): void {
  if (!form.value.title || !form.value.description) {
    return;
  }
  emit('create', { ...form.value });
}
</script>

<template>
  <section class="new-form" data-testid="new-requirement-form">
    <h3>{{ t('issues.describeTitle', '描述你的需求') }}</h3>
    <p class="new-hint">
      {{ t('issues.newHint', '和 AI 一轮轮聊清楚，确认后推送给我方开发') }}
    </p>
    <form class="ff-form-grid" @submit.prevent="submit">
      <label class="field">
        {{ t('issues.titleLabel', '标题') }}
        <input v-model="form.title" data-testid="new-requirement-title" maxlength="120" required />
      </label>
      <label class="field field--full">
        {{ t('issues.wantLabel', '想要什么') }}
        <textarea
          v-model="form.description"
          data-testid="new-requirement-description"
          rows="4"
          maxlength="4000"
          required
        />
      </label>
      <BaseButton variant="primary" type="submit" :disabled="creating" data-testid="start-clarify">
        {{ creating ? t('users.creating', '创建中…') : t('issues.startClarify', '开始与 AI 梳理') }}
      </BaseButton>
    </form>
    <p v-if="createError" class="form-error" role="alert">{{ createError }}</p>
  </section>
</template>
