<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { fetchRegistrationStatus, login, register } from '@/api/auth';
import { ApiError } from '@/api/client';
import { saveSession } from '@/auth/token';
import AppLogo from '@/components/AppLogo.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 登录/注册页（P13 双模式 + P15 品牌化；P18 极简化）。登录页无会话不能取签名
 * 主题资产（已知限制，docs/09 P15）：品牌识别=平台基线 SVG（结构），观感细节由
 * --ff-* 令牌承载，主题插件仍可换整体色彩。文案经 t() 可被 locale 插件覆盖
 * （FR-SETUP-02；未激活语言包时回退中文基线）。
 */
const router = useRouter();
const mode = ref<'login' | 'register'>('login');
const registerEnabled = ref(false);
const username = ref('');
const displayName = ref('');
const password = ref('');
const error = ref<string | null>(null);
const submitting = ref(false);

onMounted(async () => {
  try {
    registerEnabled.value = (await fetchRegistrationStatus()).selfRegistrationEnabled;
  } catch {
    /* 旗标不可知按关闭处理：隐藏注册入口，登录主流程不受影响（P13 验收） */
  }
});

async function submit(): Promise<void> {
  if (submitting.value || username.value === '' || password.value === '') {
    return;
  }
  submitting.value = true;
  error.value = null;
  try {
    const result =
      mode.value === 'login'
        ? await login(username.value, password.value)
        : await register(username.value, password.value, displayName.value || username.value);
    saveSession(result.token, result.user);
    await router.push({ path: '/' });
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '操作失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}

function switchMode(target: 'login' | 'register'): void {
  mode.value = target;
  error.value = null;
}
</script>

<template>
  <main class="login-scene" data-testid="login-view">
    <div class="login-card ff-animate-rise">
      <header class="login-brand">
        <AppLogo :size="40" />
        <h1>FlexForge</h1>
        <p class="login-tagline">
          {{ t('login.tagline', '模块化数据管理系统 · 元数据驱动 · 插件化运行时') }}
        </p>
      </header>
      <form class="login-form" @submit.prevent="submit">
        <label>
          {{ t('login.username', '用户名') }}
          <input
            v-model="username"
            name="username"
            autocomplete="username"
            :pattern="mode === 'register' ? '[a-z0-9_-]{3,32}' : undefined"
            title="3-32 位小写字母/数字/下划线/连字符"
            required
          />
        </label>
        <label v-if="mode === 'register'">
          {{ t('login.displayName', '显示名') }}
          <input v-model="displayName" name="displayName" autocomplete="nickname" />
        </label>
        <label>
          {{ t('login.password', '密码') }}
          <input
            v-model="password"
            name="password"
            type="password"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            :minlength="mode === 'register' ? 8 : undefined"
            maxlength="128"
            required
          />
        </label>
        <p v-if="error" class="login-error" role="alert">{{ error }}</p>
        <BaseButton type="submit" variant="primary" :disabled="submitting">
          {{
            submitting
              ? t('login.pleaseWait', '请稍候…')
              : mode === 'login'
                ? t('login.submit', '登录')
                : t('login.register', '注册并登录')
          }}
        </BaseButton>
      </form>
      <p v-if="registerEnabled" class="login-switch">
        <a v-if="mode === 'login'" href="#" @click.prevent="switchMode('register')">
          {{ t('login.toRegister', '没有账号？自助注册') }}
        </a>
        <a v-else href="#" @click.prevent="switchMode('login')">
          {{ t('login.toLogin', '已有账号？返回登录') }}
        </a>
      </p>
    </div>
  </main>
</template>

<style scoped>
/* P18 极简基线 + P25 精修：灰阶纹理背景（结构与皮分离，彩色身份归主题令牌）、
   display 字体品牌、渐变发丝描边与柔化阴影 */
.login-scene {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background-color: var(--ff-bg);
  background-image:
    radial-gradient(
      circle at 1px 1px,
      color-mix(in srgb, var(--ff-text) 5%, transparent) 1px,
      transparent 0
    ),
    radial-gradient(
      60rem 40rem at 70% -10%,
      color-mix(in srgb, var(--ff-surface-muted) 70%, transparent),
      transparent
    );
  background-size:
    24px 24px,
    100% 100%;
}
.login-card {
  width: min(24rem, calc(100vw - 2rem));
  padding: 2.25rem;
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-lg);
  box-shadow:
    var(--ff-shadow-2),
    0 0 0 4px color-mix(in srgb, var(--ff-primary) 6%, transparent);
}
.login-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ff-space-2);
  margin-bottom: var(--ff-space-4);
  color: var(--ff-text);
}
.login-brand h1 {
  margin: 0;
  font-family: var(--ff-font-display);
  font-size: var(--ff-text-2xl);
  font-weight: 700;
  letter-spacing: 0.06em;
}
.login-tagline {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
  text-align: center;
}
.login-form label {
  display: block;
  margin-bottom: 0.9rem;
}
.login-form input {
  display: block;
  width: 100%;
  margin-top: var(--ff-space-1);
  padding: 0.55rem 0.7rem;
  box-sizing: border-box;
}
.login-error {
  color: var(--ff-danger);
}
.login-switch {
  margin: var(--ff-space-3) 0 0;
  text-align: center;
  font-size: var(--ff-text-sm);
}
</style>
