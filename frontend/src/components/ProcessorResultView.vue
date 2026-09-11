<script setup lang="ts">
import { ref } from 'vue';

import type { ProcessorResult } from '@/api/processors';
import { downloadProcessorArtifact } from '@/api/processors';
import { apiErrorMessage } from '@/api/client';
import BaseButton from '@/components/ui/BaseButton.vue';
import ChartCanvas from '@/components/ui/ChartCanvas.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 处理器结果渲染（P20 建/P23 抽公用）：table 结构表 / summary 指标卡 /
 * chart 图表（FR-CHART-01）/ file 产物下载卡（FR-PLUGIN-14，下载错误本地呈现）。
 * 平台白名单渲染（S5：插件无前端代码）。
 */
defineProps<{ result: ProcessorResult }>();

const downloadError = ref<string | null>(null);

async function download(artifactId: string, filename: string): Promise<void> {
  try {
    downloadError.value = null;
    await downloadProcessorArtifact(artifactId, filename);
  } catch (e) {
    downloadError.value = apiErrorMessage(e, t('tools.downloadFailed', '产物下载失败，请稍后重试'));
  }
}
</script>

<template>
  <div v-if="result.kind === 'table'" class="processor-table-wrap" data-testid="result-table">
    <div class="table-scroll">
      <table>
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
  </div>
  <dl v-else-if="result.kind === 'summary'" class="processor-summary" data-testid="result-summary">
    <template v-for="item in result.items" :key="item.label">
      <dt>{{ item.label }}</dt>
      <dd>{{ item.value === null ? '—' : String(item.value) }}</dd>
    </template>
  </dl>
  <ChartCanvas
    v-else-if="result.kind === 'chart'"
    :type="result.chartType"
    :title="result.title"
    :categories="result.categories"
    :values="result.values"
  />
  <div v-else class="artifact-card" data-testid="result-file">
    <p class="artifact-name">{{ result.filename }}</p>
    <p class="artifact-meta">
      {{ Math.max(1, Math.round(result.sizeBytes / 1024)) }} KB ·
      {{ t('tools.artifactHint', '产物 10 分钟内可重复下载') }}
    </p>
    <BaseButton
      variant="primary"
      :data-testid="`download-${result.artifactId}`"
      @click="download(result.artifactId, result.filename)"
    >
      {{ t('tools.downloadArtifact', '下载产物') }}
    </BaseButton>
    <p v-if="downloadError" class="form-error" role="alert">{{ downloadError }}</p>
  </div>
</template>

<style scoped>
.table-scroll {
  overflow-x: auto;
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--ff-text-sm);
}
th,
td {
  padding: var(--ff-space-2);
  text-align: left;
  border-bottom: 1px solid var(--ff-border-soft);
  white-space: nowrap;
}
th {
  background: var(--ff-surface-muted);
  font-weight: 600;
}
tbody tr:last-child td {
  border-bottom: none;
}
.processor-summary {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: var(--ff-space-2);
}
.processor-summary dt {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.processor-summary dd {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}
.artifact-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ff-space-2);
  padding: var(--ff-space-3);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
}
.artifact-name,
.artifact-meta {
  margin: 0;
}
.artifact-meta {
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
</style>
