<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError } from '@/api/client';
import { fetchAiConfig, updateAiConfig, type AiConfigView } from '@/api/settings';
import { session } from '@/auth/token';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

/**
 * 设置页（P15，FR-SETUP-01）：AI 模型运行时配置（ADMIN）——provider/base-url/
 * model/API Key（留空=保持不变，明文零回显只显掩码；清除开关）。语言切换等
 * 用户级设置随 P15 迭代 3 的 locale 通道加入。
 */
const isAdmin = computed(() => session.user?.roles.includes('ADMIN') ?? false);

const config = ref<AiConfigView | null>(null);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);

const provider = ref<'fixture' | 'http'>('fixture');
const baseUrl = ref('');
const model = ref('');
const apiKey = ref('');
const clearKey = ref(false);
const saving = ref(false);
const formError = ref<string | null>(null);
const notice = ref<string | null>(null);

async function load(): Promise<void> {
  if (!isAdmin.value) {
    state.value = 'empty';
    return;
  }
  state.value = 'loading';
  try {
    config.value = await fetchAiConfig();
    provider.value = config.value.provider;
    baseUrl.value = config.value.baseUrl ?? '';
    model.value = config.value.model ?? '';
    state.value = 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value =
      e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : null;
  }
}

/** http 形态完整性（表单 required 为第一层，此处兜底防编程提交）。 */
function httpIncomplete(): boolean {
  return provider.value === 'http' && (baseUrl.value.trim() === '' || model.value.trim() === '');
}

async function submit(): Promise<void> {
  if (saving.value || httpIncomplete()) {
    return;
  }
  saving.value = true;
  formError.value = null;
  notice.value = null;
  try {
    const next = await updateAiConfig({
      provider: provider.value,
      baseUrl: baseUrl.value.trim() || null,
      model: model.value.trim() || null,
      apiKey: apiKey.value.trim() === '' ? null : apiKey.value.trim(),
      clearApiKey: clearKey.value || undefined,
    });
    config.value = next;
    apiKey.value = '';
    clearKey.value = false;
    notice.value = '已保存，配置即时生效（下一次模型调用按新配置路由）';
  } catch (e) {
    formError.value =
      e instanceof ApiError
        ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}`
        : '保存失败，请稍后重试';
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="settings-view" data-testid="settings-view">
    <header class="settings-header"><h2>设置</h2></header>

    <p v-if="!isAdmin" class="hint">界面语言等用户级设置即将上线；AI 模型配置请联系管理员。</p>
    <StateView v-else-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">暂无可配置项</p>
    </StateView>
    <ComponentCard v-else title="AI 模型" subtitle="Issue 澄清与规格生成的模型通道">
      <form class="ai-form" data-testid="ai-config-form" @submit.prevent="submit">
        <label>
          提供方
          <select v-model="provider" data-testid="provider-select">
            <option value="fixture">fixture（离线脚本，答辩默认）</option>
            <option value="http">http（OpenAI 兼容接口）</option>
          </select>
        </label>
        <template v-if="provider === 'http'">
          <label>
            Base URL
            <input
              v-model="baseUrl"
              name="baseUrl"
              placeholder="https://api.example.com/v1/chat/completions"
              maxlength="500"
              required
            />
          </label>
          <label>
            模型
            <input
              v-model="model"
              name="model"
              placeholder="gpt-4o-mini"
              maxlength="100"
              required
            />
          </label>
        </template>
        <label>
          API Key
          <input
            v-model="apiKey"
            name="apiKey"
            type="password"
            autocomplete="new-password"
            maxlength="4096"
            :placeholder="
              config?.apiKeyConfigured
                ? `已配置（${config.apiKeyHint ?? '掩码'}）——留空保持不变`
                : '未配置'
            "
          />
        </label>
        <label v-if="config?.apiKeyConfigured" class="inline-option">
          <input v-model="clearKey" type="checkbox" />
          清除已保存的 API Key
        </label>
        <p v-if="config?.apiKeyStale" class="form-error" role="alert">
          已保存的密钥无法解密（服务端密钥可能已轮换），请重新录入。
        </p>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
        <p v-if="notice" class="op-notice" role="status">{{ notice }}</p>
        <div class="drawer-actions">
          <BaseButton type="submit" variant="primary" :disabled="saving">
            {{ saving ? '保存中…' : '保存' }}
          </BaseButton>
        </div>
      </form>
    </ComponentCard>
  </section>
</template>

<style scoped>
.settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  color: var(--ff-text-muted);
}
.ai-form label {
  display: block;
  margin-bottom: var(--ff-space-3);
}
.ai-form input,
.ai-form select {
  display: block;
  width: 100%;
  margin-top: var(--ff-space-1);
  padding: var(--ff-space-2);
  box-sizing: border-box;
}
.inline-option {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
}
.inline-option input {
  width: auto;
  margin-top: 0;
}
.op-notice {
  color: var(--ff-primary);
  font-size: var(--ff-text-sm);
}
.drawer-actions {
  display: flex;
  gap: var(--ff-space-2);
}
</style>
