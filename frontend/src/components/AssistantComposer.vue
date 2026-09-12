<script setup lang="ts">
import { ref, watch } from 'vue';

import { KB_ACCEPT, KB_MAX_FILE_BYTES, KB_MAX_FILES } from '@/api/kb';
import AppIcon from '@/components/AppIcon.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 助手对话输入区（P29 拆出）：文本 + 附件选择（白名单/数量/尺寸客户端预检，
 * 服务端为边界）+ 发送；Enter 发送/Shift+Enter 换行/IME 组态不提交。
 * 输入与附件在发送成功后由父层递增 resetKey 清空——失败保留原稿便于重试。
 */
const props = defineProps<{ asking: boolean; resetKey: number }>();
const emit = defineEmits<{ send: [question: string, files: File[]] }>();

const question = ref('');
const files = ref<File[]>([]);
const localError = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

watch(
  () => props.resetKey,
  () => {
    question.value = '';
    files.value = [];
    localError.value = null;
  },
);

function extOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot >= 0 ? name.slice(dot + 1).toLowerCase() : '';
}

function onPick(event: Event): void {
  const input = event.target as HTMLInputElement;
  const picked = Array.from(input.files ?? []);
  input.value = '';
  localError.value = null;
  const whitelist = KB_ACCEPT.replace(/\./g, '').split(',');
  for (const file of picked) {
    if (files.value.length >= KB_MAX_FILES) {
      localError.value = t('assistant.attachTooMany', '单次最多 3 个附件');
      break;
    }
    if (!whitelist.includes(extOf(file.name))) {
      localError.value = t('assistant.attachBadType', '存在不支持的附件类型');
      continue;
    }
    if (file.size > KB_MAX_FILE_BYTES) {
      localError.value = t('assistant.attachTooLarge', '附件超过 10MB 上限');
      continue;
    }
    files.value = [...files.value, file];
  }
}

function removeFile(index: number): void {
  files.value = files.value.filter((_, i) => i !== index);
  localError.value = null;
}

function submit(): void {
  const text = question.value.trim();
  if (props.asking || text === '') {
    return;
  }
  emit('send', text, files.value);
}

/** Enter 发送；Shift+Enter 换行；IME 组态中的回车是选词不是提交。 */
function onKeydown(event: KeyboardEvent): void {
  if (!event.shiftKey && !event.isComposing) {
    event.preventDefault();
    submit();
  }
}
</script>

<template>
  <div class="composer-wrap">
    <ul v-if="files.length > 0" class="pending" data-testid="assistant-pending">
      <li v-for="(file, index) in files" :key="file.name + index">
        <span class="pending-name">{{ file.name }}</span>
        <span class="pending-size">{{ Math.max(1, Math.round(file.size / 1024)) }} KB</span>
        <button
          type="button"
          class="pending-remove"
          :aria-label="t('assistant.attachRemove', '移除附件')"
          @click="removeFile(index)"
        >
          ×
        </button>
      </li>
    </ul>
    <p v-if="localError" class="attach-error" role="alert" data-testid="assistant-attach-error">
      {{ localError }}
    </p>
    <div class="composer">
      <input
        ref="fileInput"
        type="file"
        multiple
        :accept="KB_ACCEPT"
        class="file-input"
        data-testid="assistant-file-input"
        @change="onPick"
      />
      <BaseButton
        variant="ghost"
        :aria-label="t('assistant.attach', '添加附件')"
        :disabled="asking"
        data-testid="assistant-attach"
        @click="fileInput?.click()"
      >
        <AppIcon name="clip" :size="16" />
      </BaseButton>
      <textarea
        v-model="question"
        rows="2"
        :placeholder="t('assistant.placeholder', '输入你的问题…')"
        data-testid="assistant-input"
        :disabled="asking"
        @keydown.enter="onKeydown"
      />
      <BaseButton
        variant="primary"
        :aria-label="t('common.send', '发送')"
        :disabled="asking || question.trim() === ''"
        data-testid="assistant-send"
        @click="submit"
      >
        <AppIcon name="send" :size="16" />
      </BaseButton>
    </div>
    <p class="composer-hint">
      {{ t('assistant.composerHint', 'Enter 发送，Shift + Enter 换行') }}
    </p>
  </div>
</template>

<style scoped>
.composer-wrap {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-1);
}
.pending {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: var(--ff-space-2);
  margin: 0;
  padding: 0;
}
.pending li {
  display: inline-flex;
  align-items: center;
  gap: var(--ff-space-1);
  font-size: var(--ff-text-xs, 0.75rem);
  border: 1px solid var(--ff-border);
  border-radius: 999px;
  padding: 2px var(--ff-space-2);
  background: var(--ff-surface-muted);
}
.pending-size {
  color: var(--ff-text-muted);
}
.pending-remove {
  border: none;
  background: none;
  color: var(--ff-text-muted);
  cursor: pointer;
  padding: 0 0 0 var(--ff-space-1);
  font-size: var(--ff-text-sm);
}
.attach-error {
  color: var(--ff-danger);
  font-size: var(--ff-text-sm);
  margin: 0;
}
.file-input {
  display: none;
}
.composer {
  display: flex;
  gap: var(--ff-space-2);
  align-items: flex-end;
}
.composer textarea {
  flex: 1;
  resize: vertical;
}
.composer-hint {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-xs, 0.75rem);
  margin: 0;
}
</style>
