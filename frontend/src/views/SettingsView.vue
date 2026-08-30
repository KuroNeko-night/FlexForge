<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError } from '@/api/client';
import { fetchAiConfig, updateAiConfig, type AiConfigView } from '@/api/settings';
import { session } from '@/auth/token';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';
import {
  availableLanguages,
  currentLanguage,
  LANGUAGE_LABELS,
  setLanguage,
  t,
} from '@/registry/localeRegistry';

/**
 * 设置页（P15，FR-SETUP-01/02）：AI 模型运行时配置（ADMIN）+ 界面语言切换
 * （全员；语言内容来自 locale 插件——未激活语言包时仅平台中文基线，FR-SETUP-02）。
 * 文案经 t() 可被语言包覆盖。
 */
const isAdmin = computed(() => session.user?.roles.includes('ADMIN') ?? false);
const language = computed(() => currentLanguage());
const languageOptions = computed(() =>
  availableLanguages().map((lang) => ({ value: lang, label: LANGUAGE_LABELS[lang] ?? lang })),
);

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

/** 勾选清除时联动清空输入（PR #35 审查 P2：与新密钥互斥，防"以为换了实被清空"）。 */
function onClearToggle(): void {
  if (clearKey.value) {
    apiKey.value = '';
  }
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
    <header class="settings-header">
      <h2>{{ t('settings.title', '设置') }}</h2>
    </header>

    <ComponentCard
      :title="t('settings.language', '界面语言')"
      :subtitle="t('settings.languageHint', '语言内容由 locale 插件分发，停用即回退中文基线')"
    >
      <label class="inline-option language-row">
        {{ t('settings.currentLanguage', '当前语言') }}
        <select :value="language" data-testid="language-select" disabled>
          <option v-for="option in languageOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <div class="language-options" data-testid="language-options">
        <BaseButton
          v-for="option in languageOptions"
          :key="option.value"
          :variant="option.value === language ? 'primary' : undefined"
          size="sm"
          :data-lang="option.value"
          @click="setLanguage(option.value)"
        >
          {{ option.label }}
        </BaseButton>
      </div>
      <p class="hint">
        {{
          languageOptions.length > 1
            ? t('settings.languageAvailable', '可选语言来自已激活的语言插件')
            : t('settings.languagePluginMissing', '仅平台中文基线——安装语言插件后此处出现更多选项')
        }}
      </p>
    </ComponentCard>

    <p v-if="!isAdmin" class="hint">
      {{ t('settings.aiContactAdmin', 'AI 模型配置请联系管理员。') }}
    </p>
    <StateView v-else-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">{{ t('settings.empty', '暂无可配置项') }}</p>
    </StateView>
    <ComponentCard
      v-else
      :title="t('settings.ai', 'AI 模型')"
      :subtitle="t('settings.aiHint', 'Issue 澄清与规格生成的模型通道')"
    >
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
            :disabled="clearKey"
            :placeholder="
              clearKey
                ? '将清除已保存的密钥'
                : config?.apiKeyConfigured
                  ? `已配置（${config.apiKeyHint ?? '掩码'}）——留空保持不变`
                  : '未配置'
            "
          />
        </label>
        <label v-if="config?.apiKeyConfigured" class="inline-option">
          <input
            v-model="clearKey"
            type="checkbox"
            data-testid="clear-key"
            @change="onClearToggle"
          />
          清除已保存的 API Key（与录入新密钥互斥）
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
.settings-view {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-4);
}
.settings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  margin: var(--ff-space-2) 0 0;
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.language-row {
  margin-bottom: var(--ff-space-2);
}
.language-row select {
  padding: var(--ff-space-2);
}
.language-options {
  display: flex;
  gap: var(--ff-space-2);
  flex-wrap: wrap;
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
