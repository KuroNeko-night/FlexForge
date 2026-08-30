<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { ApiError } from '@/api/client';
import {
  fetchPreview,
  generatePlugin,
  ISSUE_STATUS_LABELS,
  ISSUE_TRANSITIONS,
  saveSpec,
  transitionIssue,
  transitionRequiresReason,
  type GenerateOutcome,
  type IssueRecord,
  type SpecPreview,
  type SpecRevision,
} from '@/api/issues';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * 开发者面板（P15，DEVELOPER；服务端 @RequireRole 为安全边界）：
 * 状态迁移（须原因的旁路强制填写）、规格查看/手工编辑（FR-ISSUE-06 模型
 * 不可用兜底）、插件资源预览、生成骨架（FR-ISSUE-05）。
 */
const props = defineProps<{ issue: IssueRecord; spec: SpecRevision | null }>();
const emit = defineEmits<{
  updated: [issue: IssueRecord];
  reload: [];
  specSaved: [spec: SpecRevision];
}>();

const targets = computed(() => ISSUE_TRANSITIONS[props.issue.status] ?? []);
const target = ref<(typeof targets.value)[number] | ''>('');
const reason = ref('');
const transitioning = ref(false);
const transitionError = ref<string | null>(null);

const specDraft = ref('');
const savingSpec = ref(false);
const specError = ref<string | null>(null);
const preview = ref<SpecPreview | null>(null);
const previewing = ref(false);

const generating = ref(false);
const generateError = ref<string | null>(null);
const generated = ref<GenerateOutcome | null>(null);

watch(
  () => props.spec,
  (next) => {
    specDraft.value = next?.specJson ?? '';
    preview.value = null;
  },
  { immediate: true },
);
watch(
  () => props.issue.id,
  () => {
    target.value = '';
    reason.value = '';
    transitionError.value = null;
    specError.value = null;
    generateError.value = null;
    generated.value = null;
  },
);

async function submitTransition(): Promise<void> {
  if (transitioning.value || target.value === '') {
    return;
  }
  if (transitionRequiresReason(target.value) && reason.value.trim() === '') {
    transitionError.value = '该迁移必须填写原因';
    return;
  }
  transitioning.value = true;
  transitionError.value = null;
  try {
    emit('updated', await transitionIssue(props.issue.id, target.value, reason.value.trim()));
    target.value = '';
    reason.value = '';
  } catch (e) {
    transitionError.value = e instanceof ApiError ? e.message : '迁移失败，请稍后重试';
  } finally {
    transitioning.value = false;
  }
}

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
    specError.value = e instanceof ApiError ? e.message : '保存失败：内容须为合法 JSON 规格';
  } finally {
    savingSpec.value = false;
  }
}

async function loadPreview(): Promise<void> {
  if (previewing.value) {
    return;
  }
  previewing.value = true;
  try {
    preview.value = await fetchPreview(props.issue.id);
  } catch (e) {
    preview.value = {
      valid: false,
      resources: {},
      errors: [e instanceof ApiError ? e.message : '预览失败'],
    };
  } finally {
    previewing.value = false;
  }
}

async function submitGenerate(): Promise<void> {
  if (generating.value) {
    return;
  }
  if (!window.confirm('确认按当前规格生成插件骨架并激活进入测试环境？')) {
    return;
  }
  generating.value = true;
  generateError.value = null;
  try {
    generated.value = await generatePlugin(props.issue.id);
    emit('updated', generated.value.issue);
  } catch (e) {
    generateError.value = e instanceof ApiError ? e.message : '生成失败，请稍后重试';
  } finally {
    generating.value = false;
  }
}
</script>

<template>
  <ComponentCard title="开发者面板" subtitle="状态迁移 / 规格 / 预览 / 生成">
    <div class="dev-section" data-testid="transition-section">
      <h4>状态迁移</h4>
      <div class="inline-form">
        <select v-model="target" :disabled="targets.length === 0" data-testid="transition-target">
          <option value="" disabled>选择目标状态</option>
          <option v-for="item in targets" :key="item" :value="item">
            {{ ISSUE_STATUS_LABELS[item] }}
          </option>
        </select>
        <input
          v-model="reason"
          placeholder="迁移原因（旁路必填）"
          maxlength="500"
          :disabled="targets.length === 0"
        />
        <BaseButton
          variant="primary"
          :disabled="transitioning || target === ''"
          @click="submitTransition"
        >
          {{ transitioning ? '执行中…' : '执行迁移' }}
        </BaseButton>
      </div>
      <p v-if="transitionError" class="form-error" role="alert">{{ transitionError }}</p>
      <p v-if="targets.length === 0" class="hint">当前为终态，无可用迁移。</p>
    </div>

    <div class="dev-section" data-testid="spec-section">
      <h4>
        规格
        <span v-if="spec" class="hint">
          修订 {{ spec.revision }} · {{ spec.valid ? '校验通过' : `校验未通过：${spec.validationErrors}` }}
        </span>
      </h4>
      <textarea
        v-model="specDraft"
        class="spec-editor"
        rows="10"
        spellcheck="false"
        placeholder='{"schemaVersion":1, ...}（可让 AI 澄清生成，或在此手工编写）'
      />
      <div class="inline-form">
        <BaseButton variant="primary" :disabled="savingSpec" @click="submitSpec">
          {{ savingSpec ? '保存中…' : '保存规格' }}
        </BaseButton>
        <BaseButton :disabled="previewing" @click="loadPreview">
          {{ previewing ? '预览中…' : '预览插件资源' }}
        </BaseButton>
      </div>
      <p v-if="specError" class="form-error" role="alert">{{ specError }}</p>
      <div v-if="preview" class="preview" data-testid="spec-preview">
        <p v-if="preview.valid">将生成 {{ Object.keys(preview.resources).length }} 个资源文件：</p>
        <ul>
          <li v-for="(content, path) in preview.resources" :key="path">
            <code>{{ path }}</code>（{{ content.length }} 字符）
          </li>
        </ul>
        <p v-if="!preview.valid" class="form-error">规格不合法：{{ preview.errors.join('；') }}</p>
      </div>
    </div>

    <div class="dev-section" data-testid="generate-section">
      <h4>生成插件骨架</h4>
      <p class="hint">需 Issue 已批准且存在校验通过的规格；生成后自动激活进入待测试。</p>
      <BaseButton variant="primary" :disabled="generating" @click="submitGenerate">
        {{ generating ? '生成中…' : '生成并激活' }}
      </BaseButton>
      <p v-if="generateError" class="form-error" role="alert">{{ generateError }}</p>
      <p v-if="generated" class="generated" data-testid="generate-outcome">
        已生成 {{ generated.pluginId }}@{{ generated.version }}（激活
        {{ generated.activationId }}），可在插件管理页查看。
      </p>
    </div>
  </ComponentCard>
</template>

<style scoped>
.dev-section {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  padding-bottom: var(--ff-space-3);
  border-bottom: 1px dashed var(--ff-border-soft);
}
.dev-section:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.dev-section h4 {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: var(--ff-space-2);
}
.inline-form {
  display: flex;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
  align-items: center;
}
.inline-form select,
.inline-form input {
  padding: var(--ff-space-2);
}
.inline-form input {
  flex: 1;
  min-width: 12rem;
}
.spec-editor {
  width: 100%;
  box-sizing: border-box;
  padding: var(--ff-space-2);
  font-family: var(--ff-font-mono);
  font-size: var(--ff-text-sm);
  resize: vertical;
}
.hint {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
  font-weight: 400;
  margin: 0;
}
.preview {
  background: var(--ff-surface-muted);
  border-radius: var(--ff-radius-md);
  padding: var(--ff-space-2);
  font-size: var(--ff-text-sm);
}
.preview p {
  margin: 0 0 var(--ff-space-1);
}
.preview ul {
  margin: 0;
  padding-left: var(--ff-space-4);
}
.generated {
  margin: 0;
  color: var(--ff-primary);
  font-size: var(--ff-text-sm);
}
</style>
