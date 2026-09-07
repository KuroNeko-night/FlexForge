<script setup lang="ts">
import { ref, watch } from 'vue';

import {
  fetchProcessors,
  invokeProcessor,
  type ProcessorEntry,
  type ProcessorResult,
} from '@/api/processors';
import { apiErrorMessage } from '@/api/client';
import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';

/**
 * 数据处理器抽屉（P20，extension.data-processor 消费面）：该实体声明的处理器
 * 清单 → 执行（loading/错误态）→ 结果渲染（table 结构表 / summary 指标卡）。
 * 结果由平台组件渲染（S5 白名单语义不变，插件无前端代码）。
 */
const props = defineProps<{ open: boolean; entity: string }>();
const emit = defineEmits<{ close: [] }>();

const processors = ref<ProcessorEntry[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const runningKey = ref<string | null>(null);
const result = ref<ProcessorResult | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  result.value = null;
  try {
    processors.value = await fetchProcessors(props.entity);
  } catch (e) {
    error.value = apiErrorMessage(e, '处理器清单加载失败');
  } finally {
    loading.value = false;
  }
}

async function run(entry: ProcessorEntry): Promise<void> {
  runningKey.value = entry.key;
  error.value = null;
  result.value = null;
  try {
    result.value = await invokeProcessor(entry.key, props.entity);
  } catch (e) {
    error.value = apiErrorMessage(e, '处理器执行失败');
  } finally {
    runningKey.value = null;
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      void load();
    }
  },
  { immediate: true },
);
</script>

<template>
  <BaseDrawer :open="open" title="数据分析" @close="emit('close')">
    <div class="processor-panel" data-testid="processor-panel">
      <p v-if="loading" class="processor-hint">加载处理器清单…</p>
      <p v-else-if="error && processors.length === 0" class="form-error" role="alert">
        {{ error }}
      </p>
      <p v-else-if="processors.length === 0" class="processor-hint">该实体暂无可用的数据处理器</p>
      <template v-else>
        <div class="processor-list" role="list">
          <div v-for="entry in processors" :key="entry.key" class="processor-item" role="listitem">
            <div class="processor-meta">
              <span class="processor-label">{{ entry.label }}</span>
              <span class="processor-plugin">{{ entry.pluginId }}</span>
            </div>
            <BaseButton
              size="sm"
              :data-testid="`run-${entry.key}`"
              :disabled="runningKey !== null"
              @click="run(entry)"
            >
              {{ runningKey === entry.key ? '计算中…' : '执行' }}
            </BaseButton>
          </div>
        </div>

        <p v-if="error" class="form-error" role="alert">{{ error }}</p>

        <section v-if="result" class="processor-result" data-testid="processor-result">
          <h3>处理结果</h3>
          <div v-if="result.kind === 'table'" class="processor-table-wrap">
            <table class="dynamic-table">
              <thead>
                <tr>
                  <th v-for="column in result.columns" :key="column.name" scope="col">
                    {{ column.label }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in result.rows" :key="index">
                  <td v-for="(cell, cellIndex) in row" :key="cellIndex">
                    {{ cell === null || cell === undefined ? '—' : String(cell) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <dl v-else class="processor-summary">
            <template v-for="item in result.items" :key="item.label">
              <dt>{{ item.label }}</dt>
              <dd>{{ item.value === null ? '—' : String(item.value) }}</dd>
            </template>
          </dl>
        </section>
      </template>
    </div>
  </BaseDrawer>
</template>

<style scoped>
.processor-panel {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-3);
}
.processor-hint {
  color: var(--ff-text-muted);
}
.processor-list {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.processor-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ff-space-3);
  padding: var(--ff-space-2) var(--ff-space-3);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
}
.processor-meta {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
}
.processor-label {
  font-weight: 600;
}
.processor-plugin {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.processor-result h3 {
  margin: 0 0 var(--ff-space-2);
  font-size: var(--ff-text-md);
}
.processor-table-wrap {
  overflow-x: auto;
}
.processor-summary {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--ff-space-2) var(--ff-space-3);
  margin: 0;
}
.processor-summary dt {
  font-weight: 600;
  color: var(--ff-text-muted);
}
.processor-summary dd {
  margin: 0;
}
</style>
