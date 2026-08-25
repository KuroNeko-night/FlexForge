<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { login } from '@/api/auth';
import { ApiError } from '@/api/client';
import { saveSession } from '@/auth/token';

const router = useRouter();
const username = ref('');
const password = ref('');
const error = ref<string | null>(null);
const submitting = ref(false);

async function submit(): Promise<void> {
  if (submitting.value || username.value === '' || password.value === '') {
    return;
  }
  submitting.value = true;
  error.value = null;
  try {
    const result = await login(username.value, password.value);
    saveSession(result.token, result.user);
    await router.push({ path: '/' });
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <main class="login-view">
    <h1>FlexForge 登录</h1>
    <form class="login-form" @submit.prevent="submit">
      <label>
        用户名
        <input v-model="username" name="username" autocomplete="username" required />
      </label>
      <label>
        密码
        <input
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
        />
      </label>
      <p v-if="error" class="login-error" role="alert">{{ error }}</p>
      <button type="submit" :disabled="submitting">
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>
  </main>
</template>
