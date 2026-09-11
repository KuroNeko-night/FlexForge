<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { assignRoles, createUser, listUsers, updateStatus, type SystemUser } from '@/api/system';
import { session } from '@/auth/token';
import BaseButton from '@/components/ui/BaseButton.vue';
import BaseSwitch from '@/components/ui/BaseSwitch.vue';
import StateView from '@/components/StateView.vue';
import UserBatchBar from '@/components/UserBatchBar.vue';
import UserCreateDrawer from '@/components/UserCreateDrawer.vue';
import UserRolesDrawer from '@/components/UserRolesDrawer.vue';
import { useUserBatch } from '@/composables/useUserBatch';
import { t } from '@/registry/localeRegistry';

/**
 * 系统管理：用户管理页（docs/09 P12.5 缺陷③ + P13 停启用）。
 * 消费 P03/P13 后端 API（list/create/roles/status，ADMIN）；基建组件构成交互。
 */
const users = ref<SystemUser[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);
const statusError = ref<string | null>(null);
const statusPending = ref<number | null>(null);
const selfId = computed(() => session.user?.id ?? null);

const drawerOpen = ref(false);
const creating = ref(false);
const formError = ref<string | null>(null);
const rolesDraft = ref<{ user: SystemUser; roles: string[] } | null>(null);
const rolesError = ref<string | null>(null);

/** 批量停启用（P24，FR-AUTH-05）：状态与单请求提交在 useUserBatch（load 为函数声明，可前置引用）。 */
const batch = useUserBatch(load);

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    const page = await listUsers();
    users.value = page.items;
    state.value = page.items.length === 0 ? 'empty' : 'ready';
    // 批量选择不跨快照残留（P24）：剔除已不存在的选择
    batch.prune(page.items.map((user) => user.id));
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = apiErrorMessage(e, null);
  }
}

/** 本页可选账号（自己不可批量变更——与单人路径同守卫；默认视图不含已封禁）。 */
const showBlocked = ref(false);
const blockedCount = computed(() => users.value.filter((u) => u.status === 'BLOCKED').length);
/** P26 界面净化（FR-AUTH-06）：BLOCKED 默认隐藏，开关仅会话内记忆。 */
const visibleUsers = computed(() =>
  showBlocked.value ? users.value : users.value.filter((u) => u.status !== 'BLOCKED'),
);
const selectableIds = computed(() =>
  visibleUsers.value.filter((u) => u.id !== selfId.value).map((u) => u.id),
);
const allSelected = computed(
  () => selectableIds.value.length > 0 && selectableIds.value.every((id) => batch.isSelected(id)),
);

function toggleAll(): void {
  batch.setSelection(allSelected.value ? [] : selectableIds.value);
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

/** 创建提交（表单状态在 UserCreateDrawer，P26 拆出）。 */
async function submitCreate(payload: {
  username: string;
  displayName: string;
  password: string;
  roles: string[];
}): Promise<void> {
  if (creating.value) {
    return;
  }
  creating.value = true;
  formError.value = null;
  try {
    await createUser(payload);
    drawerOpen.value = false;
    await load();
  } catch (e) {
    formError.value =
      e instanceof ApiError ? e.message : t('users.createFailed', '创建失败，请稍后重试');
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
    statusError.value =
      e instanceof ApiError ? e.message : t('users.statusFailed', '状态更新失败，请稍后重试');
  } finally {
    statusPending.value = null;
  }
}

async function submitRoles(): Promise<void> {
  const draft = rolesDraft.value;
  // P2（PR #32 审查）：空角色后端必拒——保存按钮禁用 + 兜底提示，失败留在抽屉内
  if (!draft || draft.roles.length === 0) {
    rolesError.value = t('users.rolesRequired', '至少保留一个角色');
    return;
  }
  try {
    const updated = await assignRoles(draft.user.id, draft.roles);
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u));
    rolesDraft.value = null;
    rolesError.value = null;
  } catch (e) {
    rolesError.value =
      e instanceof ApiError ? e.message : t('common.saveFailed', '保存失败，请稍后重试');
  }
}

onMounted(load);
</script>

<template>
  <section class="users-view" data-testid="users-view">
    <header class="users-header">
      <h2 class="ff-page-title">{{ t('users.title', '用户管理') }}</h2>
      <div class="users-header-actions">
        <BaseButton
          v-if="blockedCount > 0"
          data-testid="toggle-blocked"
          @click="showBlocked = !showBlocked"
        >
          {{
            showBlocked
              ? t('users.hideBlocked', '收起已封禁')
              : `${t('users.showBlocked', '显示已封禁')}（${blockedCount}）`
          }}
        </BaseButton>
        <BaseButton variant="primary" @click="drawerOpen = true">
          {{ t('users.create', '新建用户') }}
        </BaseButton>
      </div>
    </header>
    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">{{ t('users.empty', '尚无用户') }}</p>
    </StateView>
    <UserBatchBar
      v-else
      :count="batch.selectedIds.value.length"
      :running="batch.running.value"
      :error="batch.batchError.value"
      :notice="batch.batchNotice.value"
      @run="batch.run"
      @clear="batch.clear"
    />
    <table v-if="state === 'ready'" class="users-table" data-testid="users-table">
      <thead>
        <tr>
          <th scope="col" class="check-col">
            <input
              type="checkbox"
              data-testid="select-all-users"
              :checked="allSelected"
              :aria-label="t('users.selectAll', '全选本页')"
              @change="toggleAll"
            />
          </th>
          <th scope="col">{{ t('users.colUsername', '用户名') }}</th>
          <th scope="col">{{ t('users.colDisplayName', '显示名') }}</th>
          <th scope="col">{{ t('users.colRoles', '角色') }}</th>
          <th scope="col">{{ t('users.colStatus', '状态') }}</th>
          <th scope="col">{{ t('users.colActions', '操作') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="user in visibleUsers" :key="user.id">
          <td class="check-col">
            <input
              type="checkbox"
              :data-testid="`select-user-${user.id}`"
              :checked="batch.isSelected(user.id)"
              :disabled="user.id === selfId"
              :aria-label="`${t('users.select', '选择')} ${user.username}`"
              @change="batch.toggle(user.id)"
            />
          </td>
          <td>{{ user.username }}</td>
          <td>{{ user.displayName }}</td>
          <td>{{ user.roles.join('、') || '—' }}</td>
          <td>
            <BaseSwitch
              :model-value="user.status === 'ACTIVE'"
              :disabled="user.id === selfId || statusPending === user.id"
              :label="
                user.status === 'ACTIVE' ? t('users.active', '启用') : t('users.blocked', '已封禁')
              "
              @update:model-value="toggleStatus(user)"
            />
          </td>
          <td>
            <BaseButton size="sm" @click="rolesDraft = { user, roles: [...user.roles] }">
              {{ t('users.rolesBtn', '角色') }}
            </BaseButton>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-if="statusError" class="form-error" role="alert">{{ statusError }}</p>

    <UserCreateDrawer
      :open="drawerOpen"
      :creating="creating"
      :form-error="formError"
      @close="drawerOpen = false"
      @create="submitCreate"
    />

    <UserRolesDrawer
      :draft="rolesDraft"
      :error="rolesError"
      @close="rolesDraft = null"
      @toggle="toggleDraftRole"
      @save="submitRoles"
    />
  </section>
</template>

<style scoped>
.users-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.users-header-actions {
  display: flex;
  gap: var(--ff-space-2);
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
.check-col {
  width: 2.5rem;
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
