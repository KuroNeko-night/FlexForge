<script setup lang="ts">
import { computed, ref } from 'vue';

import { parseClarifyBrief } from '@/utils/clarifyBrief';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * 三段需求简报（P23，FR-ISSUE-03B，开发者/管理端展示）：口语化确认 /
 * 可行性 / agent 制作提示词——与规格版本一同落库的 briefJson 快照。
 * agentPrompt 提供一键复制（喂给实现 agent）。
 */
const props = defineProps<{ briefJson: string | null }>();

const brief = computed(() => parseClarifyBrief(props.briefJson));

const copied = ref(false);

async function copyPrompt(): Promise<void> {
  if (!brief.value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(brief.value.agentPrompt);
    copied.value = true;
    window.setTimeout(() => {
      copied.value = false;
    }, 2000);
  } catch {
    /* 剪贴板不可用（非安全上下文等）：不误报成功 */
  }
}
</script>

<template>
  <ComponentCard
    v-if="brief"
    title="三段需求简报"
    subtitle="口语化确认（用户）· 可行性与制作提示词（开发者）"
    data-testid="issue-brief"
  >
    <div class="brief-section">
      <h4>口语化需求确认<span class="audience">面向用户</span></h4>
      <p class="brief-text">{{ brief.colloquial }}</p>
    </div>
    <div class="brief-section">
      <h4>可行性说明<span class="audience">面向开发者</span></h4>
      <p class="brief-text">{{ brief.feasibility }}</p>
    </div>
    <div class="brief-section">
      <h4>
        agent 制作提示词<span class="audience">面向实现 agent</span>
        <BaseButton class="copy-button" data-testid="copy-agent-prompt" @click="copyPrompt">
          {{ copied ? '已复制' : '复制' }}
        </BaseButton>
      </h4>
      <p class="brief-text prompt">{{ brief.agentPrompt }}</p>
    </div>
  </ComponentCard>
</template>

<style scoped>
.brief-section {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
}
.brief-section h4 {
  margin: 0;
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  font-size: var(--ff-text-sm);
}
.audience {
  padding: 0 var(--ff-space-2);
  border-radius: 999px;
  font-size: var(--ff-text-xs, 0.75rem);
  font-weight: 400;
  color: var(--ff-text-muted);
  background: var(--ff-surface-muted);
}
.copy-button {
  margin-left: auto;
  font-size: var(--ff-text-xs, 0.75rem);
}
.brief-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--ff-text);
}
.brief-text.prompt {
  padding: var(--ff-space-2);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
  font-size: var(--ff-text-sm);
}
</style>
