<script setup lang="ts">
import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';
import type { SystemUser } from '@/api/system';
import { t } from '@/registry/localeRegistry';

/**
 * 角色调整抽屉（P26 从 UsersView 拆出，QG-4 行数）：纯展示——草稿与保存在父层。
 */
defineProps<{ draft: { user: SystemUser; roles: string[] } | null; error: string | null }>();
const emit = defineEmits<{ close: []; toggle: [role: string]; save: [] }>();

const ROLE_OPTIONS = ['ADMIN', 'DEVELOPER', 'USER'] as const;
</script>

<template>
  <BaseDrawer
    :open="draft !== null"
    :title="`${t('users.adjustRoles', '调整角色')}:${draft?.user.username ?? ''}`"
    @close="emit('close')"
  >
    <fieldset v-if="draft">
      <legend>{{ t('users.colRoles', '角色') }}</legend>
      <label v-for="role in ROLE_OPTIONS" :key="role" class="role-option">
        <input
          type="checkbox"
          :checked="draft.roles.includes(role)"
          @change="emit('toggle', role)"
        />
        {{ role }}
      </label>
    </fieldset>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="drawer-actions">
      <BaseButton
        variant="primary"
        :disabled="!draft || draft.roles.length === 0"
        @click="emit('save')"
      >
        {{ t('common.save', '保存') }}
      </BaseButton>
      <BaseButton variant="ghost" @click="emit('close')">
        {{ t('common.cancel', '取消') }}
      </BaseButton>
    </div>
  </BaseDrawer>
</template>

<style scoped>
.role-option {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  margin-bottom: var(--ff-space-3);
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
