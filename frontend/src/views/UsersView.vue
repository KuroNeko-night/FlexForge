<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { assignRoles, createUser, listUsers, updateStatus, type SystemUser } from '@/api/system';
import { session } from '@/auth/token';
import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';
import BaseSwitch from '@/components/ui/BaseSwitch.vue';
import StateView from '@/components/StateView.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 系统管理：用户管理页（docs/09 P12.5 缺陷③ + P13 停启用）。
 * 消费 P03/P13 后端 API（list/create/roles/status，ADMIN）；基建组件构成交互。
 */
const ROLE_OPTIONS = ['ADMIN', 'DEVELOPER', 'USER'] as const;

const users = ref<SystemUser[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);
const statusError = ref<string | null>(null);
const statusPending = ref<number | null>(null);
const selfId = computed(() => session.user?.id ?? null);

const drawerOpen = ref(false);
const creating = ref(false);
const formError = ref<string | null>(null);
const form = ref({ username: '', password: '', displayName: '' });
const formRoles = ref<string[]>(['USER']);
const rolesDraft = ref<{ user: SystemUser; roles: string[] } | null>(null);
const rolesError = ref<string | null>(null);

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    const page = await listUsers();
    users.value = page.items;
    state.value = page.items.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

function toggleDraftRole(role: string): void {
  const draft = rolesDraft.value;
  if (!draft) {
    return;
  }
  draft.roles = draft.roles.includes(role)
    ? draft.roles.filter((r) => r !== role)
    : [...draft.roles, role];
}

function toggleFormRole(role: string): void {
  formRoles.value = formRoles.value.includes(role)
    ? formRoles.value.filter((r) => r !== role)
    : [...formRoles.value, role];
}

async function submitCreate(): Promise<void> {
  if (creating.value || !form.value.username || !form.value.password) {
    return;
  }
  creating.value = true;
  formError.value = null;
  try {
    await createUser({ ...form.value, roles: formRoles.value });
    drawerOpen.value = false;
    form.value = { username: '', password: '', displayName: '' };
    formRoles.value = ['USER'];
    await load();
  } catch (e) {
    formError.value = e instanceof ApiError ? e.message : '创建失败，请稍后重试';
  } finally {
    creating.value = false;
  }
}

/** 停启用切换（P13）：受控更新（等服务端返回再改行）+失败保留原值+页面级错误提示；自己那行禁用（后端亦有守卫）。 */
async function toggleStatus(user: SystemUser): Promise<void> {
  if (statusPending.value !== null || user.id === selfId.value) {
    return;
  }
  const target = user.status === 'ACTIVE' ? 'BLOCKED' : 'ACTIVE';
  statusPending.value = user.id;
  statusError.value = null;
  try {
    const updated = await updateStatus(user.id, target);
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u));
  } catch (e) {
    statusError.value = e instanceof ApiError ? e.message : '状态更新失败，请稍后重试';
  } finally {
    statusPending.value = null;
  }
}

async function submitRoles(): Promise<void> {
  const draft = rolesDraft.value;
  // P2（PR #32 审查）：空角色后端必拒——保存按钮禁用 + 兜底提示，失败留在抽屉内
  if (!draft || draft.roles.length === 0) {
    rolesError.value = '至少保留一个角色';
    return;
  }
  try {
    const updated = await assignRoles(draft.user.id, draft.roles);
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u));
    rolesDraft.value = null;
    rolesError.value = null;
  } catch (e) {
    rolesError.value = e instanceof ApiError ? e.message : '保存失败，请稍后重试';
  }
}

onMounted(load);
</script>

<template>
  <section class="users-view" data-testid="users-view">
    <header class="users-header">
      <h2>{{ t('users.title', '用户管理') }}</h2>
      <BaseButton variant="primary" @click="drawerOpen = true">新建用户</BaseButton>
    </header>
    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">尚无用户</p>
    </StateView>
    <table v-else class="users-table" data-testid="users-table">
      <thead>
        <tr>
          <th scope="col">用户名</th>
          <th scope="col">显示名</th>
          <th scope="col">角色</th>
          <th scope="col">状态</th>
          <th scope="col">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="user in users" :key="user.id">
          <td>{{ user.username }}</td>
          <td>{{ user.displayName }}</td>
          <td>{{ user.roles.join('、') || '—' }}</td>
          <td>
            <BaseSwitch
              :model-value="user.status === 'ACTIVE'"
              :disabled="user.id === selfId || statusPending === user.id"
              :label="user.status === 'ACTIVE' ? '启用' : '停用'"
              @update:model-value="toggleStatus(user)"
            />
          </td>
          <td>
            <BaseButton size="sm" @click="rolesDraft = { user, roles: [...user.roles] }">
              角色
            </BaseButton>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-if="statusError" class="form-error" role="alert">{{ statusError }}</p>

    <BaseDrawer :open="drawerOpen" title="新建用户" @close="drawerOpen = false">
      <form class="user-form" @submit.prevent="submitCreate">
        <label>
          用户名
          <input
            v-model="form.username"
            name="username"
            autocomplete="off"
            pattern="[a-z0-9_-]{3,32}"
            title="3-32 位小写字母/数字/下划线/连字符"
            required
          />
        </label>
        <label>
          显示名
          <input v-model="form.displayName" name="displayName" autocomplete="off" required />
        </label>
        <label>
          初始口令
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
          <legend>角色</legend>
          <label v-for="role in ROLE_OPTIONS" :key="role" class="role-option">
            <input
              type="checkbox"
              :checked="formRoles.includes(role)"
              @change="toggleFormRole(role)"
            />
            {{ role }}
          </label>
        </fieldset>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
        <div class="drawer-actions">
          <BaseButton type="submit" variant="primary" :disabled="creating">
            {{ creating ? '创建中…' : '创建' }}
          </BaseButton>
          <BaseButton variant="ghost" @click="drawerOpen = false">取消</BaseButton>
        </div>
      </form>
    </BaseDrawer>

    <BaseDrawer
      :open="rolesDraft !== null"
      :title="`调整角色：${rolesDraft?.user.username ?? ''}`"
      @close="rolesDraft = null"
    >
      <fieldset v-if="rolesDraft">
        <legend>角色</legend>
        <label v-for="role in ROLE_OPTIONS" :key="role" class="role-option">
          <input
            type="checkbox"
            :checked="rolesDraft.roles.includes(role)"
            @change="toggleDraftRole(role)"
          />
          {{ role }}
        </label>
      </fieldset>
      <p v-if="rolesError" class="form-error" role="alert">{{ rolesError }}</p>
      <div class="drawer-actions">
        <BaseButton
          variant="primary"
          :disabled="!rolesDraft || rolesDraft.roles.length === 0"
          @click="submitRoles"
        >
          保存
        </BaseButton>
        <BaseButton variant="ghost" @click="rolesDraft = null">取消</BaseButton>
      </div>
    </BaseDrawer>
  </section>
</template>

<style scoped>
.users-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.users-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  overflow: hidden;
}
.users-table th,
.users-table td {
  padding: var(--ff-space-2) var(--ff-space-3);
  border-bottom: 1px solid var(--ff-border-soft);
  text-align: left;
}
.users-table thead th {
  font-size: var(--ff-text-sm);
  font-weight: 500;
  color: var(--ff-text-muted);
  border-bottom: 1px solid var(--ff-border);
}
.users-table tbody tr:last-child td {
  border-bottom: none;
}
.user-form label,
.role-option {
  display: block;
  margin-bottom: var(--ff-space-3);
}
.user-form input {
  display: block;
  width: 100%;
  margin-top: var(--ff-space-1);
  padding: var(--ff-space-2);
  box-sizing: border-box;
}
.role-option {
  margin-bottom: var(--ff-space-2);
}
.drawer-actions {
  display: flex;
  gap: var(--ff-space-2);
  margin-top: var(--ff-space-3);
}
</style>
