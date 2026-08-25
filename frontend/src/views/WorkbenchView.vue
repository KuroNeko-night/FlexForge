<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { logout } from '@/api/auth';
import { ApiError } from '@/api/client';
import { fetchMenus } from '@/api/meta';
import type { MenuItem } from '@/api/types';
import { session } from '@/auth/token';
import StateView from '@/components/StateView.vue';
import { mergedMenus } from '@/registry/menuRegistry';

/**
 * 工作台壳（菜单 + 路由出口）：菜单 = 后端 /menus（角色已过滤）+ 前端注册项合并。
 * 元数据/菜单数据只作文本插值与路由跳转（S5）。
 */
const router = useRouter();
const menus = ref<MenuItem[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied'>('loading');
const error = ref<string | null>(null);

async function loadMenus(): Promise<void> {
  state.value = 'loading';
  try {
    menus.value = mergedMenus(await fetchMenus());
    state.value = 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value = e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : null;
  }
}

async function signOut(): Promise<void> {
  try {
    await logout();
  } catch {
    /* 登出审计失败不阻塞本地清理（服务端口径：审计降级不阻塞） */
  }
  await router.push({ path: '/login' });
}

onMounted(loadMenus);
</script>

<template>
  <div class="workbench" data-testid="workbench">
    <aside class="workbench-side">
      <p class="brand">FlexForge</p>
      <StateView v-if="state !== 'ready'" :state="state" :message="error" />
      <nav v-else aria-label="主导航">
        <ul>
          <li v-for="menu in menus" :key="menu.key">
            <router-link v-if="menu.route" :to="menu.route">{{ menu.title }}</router-link>
            <span v-else class="menu-static">{{ menu.title }}</span>
          </li>
        </ul>
      </nav>
      <div class="side-footer">
        <span class="who">{{ session.user?.displayName ?? session.user?.username }}</span>
        <button type="button" @click="signOut">登出</button>
      </div>
    </aside>
    <section class="workbench-main">
      <router-view @metadata-refreshed="loadMenus" />
    </section>
  </div>
</template>
