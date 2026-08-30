<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { fetchRegistrationStatus, login, register } from '@/api/auth';
import { ApiError } from '@/api/client';
import { saveSession } from '@/auth/token';
import BaseButton from '@/components/ui/BaseButton.vue';

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
  <main class="login-view">
    <h1>FlexForge {{ mode === 'login' ? '登录' : '注册' }}</h1>
    <form class="login-form" @submit.prevent="submit">
      <label>
        用户名
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
        显示名
        <input v-model="displayName" name="displayName" autocomplete="nickname" />
      </label>
      <label>
        密码
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
        {{ submitting ? '请稍候…' : mode === 'login' ? '登录' : '注册并登录' }}
      </BaseButton>
    </form>
    <p v-if="registerEnabled" class="login-switch">
      <a v-if="mode === 'login'" href="#" @click.prevent="switchMode('register')">
        没有账号？自助注册
      </a>
      <a v-else href="#" @click.prevent="switchMode('login')">已有账号？返回登录</a>
    </p>
  </main>
</template>
