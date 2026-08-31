<script setup lang="ts">
import { ref } from 'vue';

import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 插件包上传区（P16 从 PluginsView 拆出）：拖拽/点击选择 zip，选中即自动
 * 校验（check 状态由父级驱动），通过后导入按钮才可用——操作者无需理解
 * 校验/导入的先后契约。隐藏原生 file input，交互统一走本组件。
 */
defineProps<{
  file: File | null;
  check: 'idle' | 'checking' | 'passed' | 'failed';
  findings: string[];
  importing: boolean;
}>();
const emit = defineEmits<{
  pick: [file: File];
  clear: [];
  import: [];
}>();

const fileInput = ref<HTMLInputElement | null>(null);
const dragOver = ref(false);

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) {
    emit('pick', file);
  }
}

function onDrop(event: DragEvent): void {
  dragOver.value = false;
  const file = event.dataTransfer?.files?.[0];
  if (file) {
    emit('pick', file);
    if (fileInput.value) {
      fileInput.value.value = '';
    }
  }
}
</script>

<template>
  <div
    class="dropzone"
    :class="{ 'dropzone--over': dragOver, 'dropzone--passed': check === 'passed' }"
    data-testid="install-bar"
    @click="fileInput?.click()"
    @dragover.prevent="dragOver = true"
    @dragleave="dragOver = false"
    @drop.prevent="onDrop"
  >
    <input
      ref="fileInput"
      type="file"
      class="file-hidden"
      accept=".zip,application/zip"
      data-testid="plugin-file"
      @change="onFileChange"
    />
    <template v-if="!file">
      <p class="dropzone-title">拖拽插件包到此处，或点击选择文件</p>
      <p class="dropzone-hint">支持 zip 格式 · 选择后自动校验</p>
    </template>
    <template v-else>
      <p class="dropzone-file">{{ file.name }}</p>
      <p class="dropzone-status" :data-state="check">
        {{
          check === 'checking'
            ? '校验中…'
            : check === 'passed'
              ? '校验通过，可导入'
              : check === 'failed'
                ? '校验未通过，请更换文件'
                : ''
        }}
      </p>
      <ul v-if="findings.length > 0" class="dropzone-findings">
        <li v-for="item in findings" :key="item">{{ item }}</li>
      </ul>
      <div class="dropzone-actions">
        <BaseButton
          variant="primary"
          :disabled="check !== 'passed' || importing"
          data-testid="import-button"
          @click.stop="emit('import')"
        >
          {{ importing ? '导入中…' : '导入' }}
        </BaseButton>
        <BaseButton variant="ghost" @click.stop="emit('clear')">移除文件</BaseButton>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dropzone {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ff-space-1);
  padding: var(--ff-space-4);
  border: 1.5px dashed var(--ff-border);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface);
  cursor: pointer;
  text-align: center;
  transition:
    border-color var(--ff-motion-fast) var(--ff-ease),
    background var(--ff-motion-fast) var(--ff-ease);
}
.dropzone:hover,
.dropzone--over {
  border-color: var(--ff-primary);
  background: color-mix(in srgb, var(--ff-primary) 5%, var(--ff-surface));
}
.dropzone--passed {
  border-style: solid;
}
.dropzone-title {
  margin: 0;
  font-weight: 600;
}
.dropzone-hint,
.dropzone-status,
.dropzone-findings {
  margin: 0;
  font-size: var(--ff-text-sm);
}
.dropzone-hint {
  color: var(--ff-text-muted);
}
.dropzone-status[data-state='passed'] {
  color: var(--ff-primary);
}
.dropzone-status[data-state='failed'],
.dropzone-findings {
  color: var(--ff-danger);
}
.dropzone-file {
  margin: 0;
  font-family: var(--ff-font-mono);
  font-size: var(--ff-text-sm);
  word-break: break-all;
}
.dropzone-findings {
  padding: 0;
  list-style: none;
  max-width: 100%;
}
.dropzone-actions {
  display: flex;
  gap: var(--ff-space-2);
  margin-top: var(--ff-space-2);
}
.file-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}
</style>
