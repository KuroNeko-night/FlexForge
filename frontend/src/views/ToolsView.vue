<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import {
  fetchProcessors,
  invokeFileProcessor,
  type ProcessorEntry,
  type ProcessorResult,
} from '@/api/processors';
import ProcessorResultView from '@/components/ProcessorResultView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';
import StateView from '@/components/StateView.vue';

/**
 * 文件工具页（P23，FR-PLUGIN-14 消费面）：ACTIVE 的文件输入处理器卡片——
 * 上传表格文件 → 受控执行 → 产物下载 / 分析结果渲染。平台能力页（空态=
 * 无文件处理器，NFR-SKEL-01）；执行与 invoke 同权（ADMIN/USER，S2 服务端）。
 */
const processors = ref<ProcessorEntry[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'empty' | 'denied'>('loading');
const error = ref<string | null>(null);

/** 每处理器一份执行态（文件选择/运行中/结果/错误），互不干扰。 */
interface ToolState {
  file: File | null;
  running: boolean;
  result: ProcessorResult | null;
  runError: string | null;
}

const toolStates = ref<Record<string, ToolState>>({});

function toolOf(entry: ProcessorEntry): ToolState {
  return (
    toolStates.value[entry.key] ?? { file: null, running: false, result: null, runError: null }
  );
}

function patch(entry: ProcessorEntry, patchValue: Partial<ToolState>): void {
  toolStates.value = {
    ...toolStates.value,
    [entry.key]: { ...toolOf(entry), ...patchValue },
  };
}

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    const all = await fetchProcessors();
    processors.value = all.filter((entry) => entry.inputMode === 'file');
    state.value = processors.value.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

function acceptHint(entry: ProcessorEntry): string {
  const exts = (entry.accept ?? []).map((ext) => `.${ext}`).join(' / ');
  return `${exts} · ≤${entry.maxInputMB ?? 5}MB`;
}

async function run(entry: ProcessorEntry): Promise<void> {
  const tool = toolOf(entry);
  if (tool.running || !tool.file) {
    return;
  }
  patch(entry, { running: true, runError: null, result: null });
  try {
    const result = await invokeFileProcessor(entry.key, tool.file);
    patch(entry, { running: false, result });
  } catch (e) {
    patch(entry, { running: false, runError: apiErrorMessage(e, '处理器执行失败，请稍后重试') });
  }
}

function onFileChange(entry: ProcessorEntry, event: Event): void {
  const input = event.target as HTMLInputElement;
  patch(entry, { file: input.files?.[0] ?? null, result: null, runError: null });
}

onMounted(load);
</script>

<template>
  <section class="tools-view" data-testid="tools-view">
    <header class="tools-header">
      <h2 class="ff-page-title">文件工具</h2>
      <p class="tools-subtitle">上传表格文件，交给插件处理——清洗、转换或分析后下载结果</p>
    </header>
    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">
        暂无文件处理器，安装提供文件处理能力的插件后此处可用的工具会出现
      </p>
    </StateView>
    <div v-else class="tools-grid">
      <ComponentCard
        v-for="entry in processors"
        :key="entry.key"
        :title="entry.label"
        :subtitle="`${entry.pluginId} · ${acceptHint(entry)}`"
      >
        <div class="tool-card" :data-testid="`tool-${entry.key}`">
          <label class="file-row">
            <input
              type="file"
              :data-testid="`file-input-${entry.key}`"
              :accept="(entry.accept ?? []).map((ext) => '.' + ext).join(',')"
              @change="onFileChange(entry, $event)"
            />
          </label>
          <div class="run-row">
            <BaseButton
              variant="primary"
              :data-testid="`run-file-${entry.key}`"
              :disabled="toolOf(entry).running || !toolOf(entry).file"
              @click="run(entry)"
            >
              {{ toolOf(entry).running ? '处理中…' : '执行' }}
            </BaseButton>
            <span v-if="toolOf(entry).file" class="file-name">{{ toolOf(entry).file?.name }}</span>
          </div>
          <p v-if="toolOf(entry).runError" class="form-error" role="alert">
            {{ toolOf(entry).runError }}
          </p>
          <ProcessorResultView v-if="toolOf(entry).result" :result="toolOf(entry).result!" />
        </div>
      </ComponentCard>
    </div>
  </section>
</template>

<style scoped>
.tools-view {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.tools-header h2 {
  margin: 0 0 var(--ff-space-1);
}
.tools-subtitle {
  margin: 0;
  color: var(--ff-text-muted);
}
.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(20rem, 1fr));
  gap: var(--ff-space-3);
  align-items: start;
}
.tool-card {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.file-row input {
  font-size: var(--ff-text-sm);
  max-width: 100%;
}
.run-row {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
}
.file-name {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
