<script setup lang="ts">
import { ref, watch } from 'vue';

import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 新建用户抽屉（P26 从 UsersView 拆出，QG-4 行数）：表单状态自持，
 * 提交以 create 事件上抛（API 与刷新在父层）。
 */
const props = defineProps<{ open: boolean; creating: boolean; formError: string | null }>();
const emit = defineEmits<{ close: []; create: [payload: CreateUserPayload] }>();

export interface CreateUserPayload {
  username: string;
  displayName: string;
  password: string;
  roles: string[];
}

const ROLE_OPTIONS = ['ADMIN', 'DEVELOPER', 'USER'] as const;
const form = ref({ username: '', password: '', displayName: '' });
const roles = ref<string[]>(['USER']);

watch(
  () => props.open,
  (open) => {
    if (open) {
      form.value = { username: '', password: '', displayName: '' };
      roles.value = ['USER'];
    }
  },
);

function toggleRole(role: string): void {
  roles.value = roles.value.includes(role)
    ? roles.value.filter((r) => r !== role)
    : [...roles.value, role];
}

function submit(): void {
  if (!form.value.username || !form.value.password) {
    return;
  }
  emit('create', { ...form.value, roles: roles.value });
}
</script>

<template>
  <BaseDrawer :open="open" :title="t('users.create', '新建用户')" @close="emit('close')">
    <form class="user-form" data-testid="user-create-form" @submit.prevent="submit">
      <label>
        {{ t('users.formUsername', '用户名') }}
        <input
          v-model="form.username"
          name="username"
          autocomplete="off"
          pattern="[a-z0-9_-]{3,32}"
          :title="t('users.usernameHint', '3-32 位小写字母/数字/下划线/连字符')"
          required
        />
      </label>
      <label>
        {{ t('users.formDisplayName', '显示名') }}
        <input v-model="form.displayName" name="displayName" autocomplete="off" required />
      </label>
      <label>
        {{ t('users.formPassword', '初始口令') }}
        <input
          v-model="form.password"
          name="password"
          type="password"
          autocomplete="new-password"
          minlength="8"
          maxlength="128"
          required
        />
      </label>
      <fieldset>
        <legend>{{ t('users.colRoles', '角色') }}</legend>
        <label v-for="role in ROLE_OPTIONS" :key="role" class="role-option">
          <input type="checkbox" :checked="roles.includes(role)" @change="toggleRole(role)" />
          {{ role }}
        </label>
      </fieldset>
      <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
      <div class="drawer-actions">
        <BaseButton type="submit" variant="primary" :disabled="creating">
          {{ creating ? t('users.creating', '创建中…') : t('users.formSubmit', '创建') }}
        </BaseButton>
        <BaseButton variant="ghost" @click="emit('close')">
          {{ t('common.cancel', '取消') }}
        </BaseButton>
      </div>
    </form>
  </BaseDrawer>
</template>

<style scoped>
.user-form :deep(label) {
  display: block;
  margin-bottom: var(--ff-space-3);
}
.role-option {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
}
fieldset {
  border: none;
  padding: 0;
  margin: 0 0 var(--ff-space-3);
}
.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--ff-space-2);
}
</style>
