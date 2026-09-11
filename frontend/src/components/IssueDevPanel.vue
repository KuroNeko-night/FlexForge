<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { apiErrorMessage } from '@/api/client';
import {
  generatePlugin,
  ISSUE_TRANSITIONS,
  transitionIssue,
  transitionRequiresReason,
  transitionLabel,
  type GenerateOutcome,
  type IssueRecord,
  type IssueStatusName,
  type SpecRevision,
} from '@/api/issues';
import IssueSpecSection from '@/components/IssueSpecSection.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import { t } from '@/registry/localeRegistry';

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

const confirmingGenerate = ref(false);
const generating = ref(false);
const generateError = ref<string | null>(null);
const generated = ref<GenerateOutcome | null>(null);

watch(
  () => props.issue.id,
  () => {
    reason.value = '';
    // 打开中的确认对话框跨 Issue 复用会串台（审查 P2-3）：一并复位
    pendingTarget.value = null;
    confirmingGenerate.value = false;
    transitionError.value = null;
    generateError.value = null;
    generated.value = null;
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
    transitionError.value = apiErrorMessage(
      e,
      t('issues.transitionFailed', '迁移失败，请稍后重试'),
    );
  } finally {
    transitioning.value = false;
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
    generateError.value = apiErrorMessage(e, t('issues.generateFailed', '生成失败，请稍后重试'));
  } finally {
    generating.value = false;
  }
}
</script>

<template>
  <ComponentCard
    :title="t('issues.devPanel', '开发者面板')"
    :subtitle="t('issues.devPanelSub', '状态迁移 / 规格 / 预览 / 生成')"
  >
    <div class="dev-section" data-testid="transition-section">
      <h4>{{ t('issues.transitionSection', '状态迁移') }}</h4>
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
          {{ transitionLabel(item.target) }}
        </BaseButton>
      </div>
      <p v-else class="hint">{{ t('issues.finalState', '当前为终态。') }}</p>
      <p v-if="transitioning" class="hint">{{ t('issues.transitioning', '迁移执行中…') }}</p>
      <p v-if="transitionError" class="form-error" role="alert">{{ transitionError }}</p>
    </div>

    <IssueSpecSection :issue="issue" :spec="spec" @spec-saved="emit('specSaved', $event)" />

    <div class="dev-section" data-testid="generate-section">
      <h4>{{ t('issues.generateSkeleton', '生成插件骨架') }}</h4>
      <p class="hint">
        {{
          t(
            'issues.generateHint',
            '需先批准 Issue 并保存校验通过的规格；生成后自动激活进入待测试。',
          )
        }}
      </p>
      <BaseButton variant="primary" :disabled="generating" @click="confirmingGenerate = true">
        {{
          generating
            ? t('issues.generating', '生成中…')
            : t('issues.generateActivate', '生成并激活')
        }}
      </BaseButton>
      <p v-if="generateError" class="form-error" role="alert">{{ generateError }}</p>
      <p v-if="generated" class="generated" data-testid="generate-outcome">
        {{ t('issues.generatedNote', '插件') }} {{ generated.pluginId }} {{ generated.version }}
        {{ t('issues.generatedTail', '已生成并进入测试，可在插件管理查看。') }}
      </p>
    </div>

    <ConfirmDialog
      :open="pendingTarget !== null"
      :title="pendingTarget ? transitionLabel(pendingTarget) : ''"
      :message="`将 ${issue.title} 的状态迁移为${pendingTarget ? transitionLabel(pendingTarget) : ''}？`"
      :confirm-label="pendingTarget ? transitionLabel(pendingTarget) : t('common.confirm', '确认')"
      require-reason
      :reason-label="t('issues.reason', '原因')"
      :reason-placeholder="t('issues.reasonPlaceholder', '填写迁移原因')"
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
