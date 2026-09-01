<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import {
  fetchPreview,
  generatePlugin,
  ISSUE_TRANSITIONS,
  saveSpec,
  transitionIssue,
  transitionRequiresReason,
  TRANSITION_LABELS,
  type GenerateOutcome,
  type IssueRecord,
  type IssueStatusName,
  type SpecPreview,
  type SpecRevision,
} from '@/api/issues';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';

/**
 * 开发者面板（P15，DEVELOPER；服务端 @RequireRole 为安全边界）：
 * 状态迁移（P16 改为按目标状态的按钮组——主链推进一键直达，旁路/回退在
 * 确认框内填原因）、规格查看/手工编辑（FR-ISSUE-06 模型不可用兜底）、
 * 插件资源预览、生成骨架（FR-ISSUE-05，破坏性确认走统一对话框）。
 */
const props = defineProps<{ issue: IssueRecord; spec: SpecRevision | null }>();
const emit = defineEmits<{
  updated: [issue: IssueRecord];
  specSaved: [spec: SpecRevision];
}>();

/** 主链推进=primary；回退与旁路=secondary（视觉权重即操作方向）。 */
const MAINLINE_NEXT: Partial<Record<IssueStatusName, IssueStatusName>> = {
  SUBMITTED: 'APPROVED',
  APPROVED: 'IN_TESTING',
  IN_TESTING: 'TESTED',
  TESTED: 'DONE',
};

const targets = computed(() =>
  (ISSUE_TRANSITIONS[props.issue.status] ?? []).map((target) => ({
    target,
    primary: MAINLINE_NEXT[props.issue.status] === target,
  })),
);

const pendingTarget = ref<IssueStatusName | null>(null);
const reason = ref('');
const transitioning = ref(false);
const transitionError = ref<string | null>(null);

const specDraft = ref('');
const savingSpec = ref(false);
const specError = ref<string | null>(null);
const preview = ref<SpecPreview | null>(null);
const previewing = ref(false);

const confirmingGenerate = ref(false);
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
    reason.value = '';
    // 打开中的确认对话框跨 Issue 复用会串台（审查 P2-3）：一并复位
    pendingTarget.value = null;
    confirmingGenerate.value = false;
    transitionError.value = null;
    specError.value = null;
    generateError.value = null;
    generated.value = null;
    // PR #34 审查 P2：specDraft 不依赖 props.spec 引用变化（null→null 不触发），
    // 切换 Issue 必须显式复位，否则 A 的草稿可被保存到 B
    specDraft.value = props.spec?.specJson ?? '';
    preview.value = null;
  },
  { immediate: true },
);

function requestTransition(target: IssueStatusName): void {
  transitionError.value = null;
  if (transitionRequiresReason(target)) {
    pendingTarget.value = target;
    reason.value = '';
    return;
  }
  // 主链/回退迁移：按钮即动作，直接执行（误触由按钮语义与 loading 态兜底）
  void runTransition(target, '');
}

async function runTransition(target: IssueStatusName, why: string): Promise<void> {
  if (transitioning.value) {
    return;
  }
  transitioning.value = true;
  try {
    emit('updated', await transitionIssue(props.issue.id, target, why));
    pendingTarget.value = null;
  } catch (e) {
    // 失败关闭对话框：错误显示在页面迁移区（遮罩之下看不到，审查 P2-5）
    pendingTarget.value = null;
    transitionError.value = apiErrorMessage(e, '迁移失败，请稍后重试');
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
    specError.value = apiErrorMessage(e, '保存失败：内容须为合法 JSON 规格');
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
  generating.value = true;
  generateError.value = null;
  try {
    generated.value = await generatePlugin(props.issue.id);
    confirmingGenerate.value = false;
    emit('updated', generated.value.issue);
  } catch (e) {
    // 失败关闭对话框：错误显示在页面生成区（审查 P2-5）
    confirmingGenerate.value = false;
    generateError.value = apiErrorMessage(e, '生成失败，请稍后重试');
  } finally {
    generating.value = false;
  }
}
</script>

<template>
  <ComponentCard title="开发者面板" subtitle="状态迁移 / 规格 / 预览 / 生成">
    <div class="dev-section" data-testid="transition-section">
      <h4>状态迁移</h4>
      <div v-if="targets.length > 0" class="transition-actions">
        <BaseButton
          v-for="item in targets"
          :key="item.target"
          :variant="item.primary ? 'primary' : 'secondary'"
          size="sm"
          :disabled="transitioning"
          :data-testid="`transition-${item.target}`"
          @click="requestTransition(item.target)"
        >
          {{ TRANSITION_LABELS[item.target] }}
        </BaseButton>
      </div>
      <p v-else class="hint">当前为终态。</p>
      <p v-if="transitioning" class="hint">迁移执行中…</p>
      <p v-if="transitionError" class="form-error" role="alert">{{ transitionError }}</p>
    </div>

    <div class="dev-section" data-testid="spec-section">
      <h4>
        规格
        <span v-if="spec" class="hint">
          修订 {{ spec.revision }} ·
          {{ spec.valid ? '校验通过' : `校验未通过：${spec.validationErrors}` }}
        </span>
      </h4>
      <textarea
        v-model="specDraft"
        class="spec-editor"
        rows="10"
        spellcheck="false"
        placeholder="可由 AI 澄清生成，也可在此手工编写"
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
            <code>{{ path }}</code> · {{ content.length }} 字符
          </li>
        </ul>
        <p v-if="!preview.valid" class="form-error">规格不合法：{{ preview.errors.join('；') }}</p>
      </div>
    </div>

    <div class="dev-section" data-testid="generate-section">
      <h4>生成插件骨架</h4>
      <p class="hint">需先批准 Issue 并保存校验通过的规格；生成后自动激活进入待测试。</p>
      <BaseButton variant="primary" :disabled="generating" @click="confirmingGenerate = true">
        {{ generating ? '生成中…' : '生成并激活' }}
      </BaseButton>
      <p v-if="generateError" class="form-error" role="alert">{{ generateError }}</p>
      <p v-if="generated" class="generated" data-testid="generate-outcome">
        插件 {{ generated.pluginId }} {{ generated.version }} 已生成并进入测试，可在插件管理查看。
      </p>
    </div>

    <ConfirmDialog
      :open="pendingTarget !== null"
      :title="pendingTarget ? TRANSITION_LABELS[pendingTarget] : ''"
      :message="`将 ${issue.title} 的状态迁移为${pendingTarget ? TRANSITION_LABELS[pendingTarget] : ''}？`"
      :confirm-label="pendingTarget ? TRANSITION_LABELS[pendingTarget] : '确认'"
      require-reason
      reason-label="原因"
      reason-placeholder="填写迁移原因"
      :busy="transitioning"
      @confirm="(why) => pendingTarget && runTransition(pendingTarget, why)"
      @cancel="pendingTarget = null"
    />
    <ConfirmDialog
      :open="confirmingGenerate"
      title="生成并激活"
      message="将按当前规格生成插件骨架，并自动激活进入测试环境。"
      confirm-label="生成并激活"
      :busy="generating"
      @confirm="submitGenerate"
      @cancel="confirmingGenerate = false"
    />
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
.transition-actions {
  display: flex;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
}
.inline-form {
  display: flex;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
  align-items: center;
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
