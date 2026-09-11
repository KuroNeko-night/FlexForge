<script setup lang="ts">
import { ref, watch } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import {
  fetchPreview,
  saveSpec,
  type IssueRecord,
  type SpecPreview,
  type SpecRevision,
} from '@/api/issues';
import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 开发者面板·规格段（P26 从 IssueDevPanel 拆出，QG-4 行数）：规格草稿编辑/
 * 保存（FR-ISSUE-06 手工兜底）与插件资源预览。切换 Issue 显式复位草稿
 * （PR #34 审查 P2：null→null 不触发 watch，A 的草稿不得落到 B）。
 */
const props = defineProps<{ issue: IssueRecord; spec: SpecRevision | null }>();
const emit = defineEmits<{ specSaved: [spec: SpecRevision] }>();

const specDraft = ref('');
const savingSpec = ref(false);
const specError = ref<string | null>(null);
const preview = ref<SpecPreview | null>(null);
const previewing = ref(false);

watch(
  () => [props.issue.id, props.spec] as const,
  () => {
    specDraft.value = props.spec?.specJson ?? '';
    preview.value = null;
  },
  { immediate: true },
);

async function submitSpec(): Promise<void> {
  if (savingSpec.value || specDraft.value.trim() === '') {
    return;
  }
  savingSpec.value = true;
  specError.value = null;
  try {
    const saved = await saveSpec(props.issue.id, specDraft.value.trim());
    specDraft.value = saved.specJson;
    emit('specSaved', saved);
  } catch (e) {
    specError.value = apiErrorMessage(
      e,
      t('issues.specSaveFailed', '保存失败：内容须为合法 JSON 规格'),
    );
  } finally {
    savingSpec.value = false;
  }
}

async function loadPreview(): Promise<void> {
  if (previewing.value) {
    return;
  }
  const issueId = props.issue.id;
  previewing.value = true;
  try {
    const result = await fetchPreview(issueId);
    if (props.issue.id === issueId) {
      preview.value = result;
    }
  } catch (e) {
    if (props.issue.id !== issueId) {
      return;
    }
    preview.value = {
      valid: false,
      resources: {},
      errors: [e instanceof ApiError ? e.message : t('issues.previewFailed', '预览失败')],
    };
  } finally {
    previewing.value = false;
  }
}
</script>

<template>
  <div class="dev-section" data-testid="spec-section">
    <h4>
      {{ t('issues.spec', '规格') }}
      <span v-if="spec" class="hint">
        {{ t('issues.revision', '修订') }} {{ spec.revision }} ·
        {{
          spec.valid
            ? t('issues.specValid', '校验通过')
            : `${t('issues.specInvalidPrefix', '校验未通过')}：${spec.validationErrors}`
        }}
      </span>
    </h4>
    <textarea
      v-model="specDraft"
      class="spec-editor"
      rows="10"
      spellcheck="false"
      :placeholder="t('issues.specPlaceholder', '可由 AI 澄清生成，也可在此手工编写')"
    />
    <div class="inline-form">
      <BaseButton variant="primary" :disabled="savingSpec" @click="submitSpec">
        {{ savingSpec ? t('common.saving', '保存中…') : t('issues.saveSpec', '保存规格') }}
      </BaseButton>
      <BaseButton :disabled="previewing" @click="loadPreview">
        {{
          previewing
            ? t('issues.previewing', '预览中…')
            : t('issues.previewResources', '预览插件资源')
        }}
      </BaseButton>
    </div>
    <p v-if="specError" class="form-error" role="alert">{{ specError }}</p>
    <div v-if="preview" class="preview" data-testid="spec-preview">
      <p v-if="preview.valid">
        {{ t('issues.willGenerate', '将生成') }} {{ Object.keys(preview.resources).length }}
        {{ t('issues.resourceFiles', '个资源文件') }}：
      </p>
      <ul>
        <li v-for="(content, path) in preview.resources" :key="path">
          <code>{{ path }}</code> · {{ content.length }} {{ t('issues.chars', '字符') }}
        </li>
      </ul>
      <p v-if="!preview.valid" class="form-error">
        {{ t('issues.specIllegal', '规格不合法') }}：{{ preview.errors.join('；') }}
      </p>
    </div>
  </div>
</template>
