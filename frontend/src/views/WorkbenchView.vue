<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { logout, fetchMe } from '@/api/auth';
import { ApiError } from '@/api/client';
import { fetchMenus } from '@/api/meta';
import { fetchActiveThemeAssets } from '@/api/theme';
import type { MenuItem } from '@/api/types';
import { clearSession, session } from '@/auth/token';
import StateView from '@/components/StateView.vue';
import { mergedMenus } from '@/registry/menuRegistry';
import {
  applyTokenOverrides,
  registerThemeAsset,
  syncRemovedActivations,
  themeStyle,
} from '@/registry/themeRegistry';

/**
 * 工作台壳（菜单 + 路由出口）：菜单 = 后端 /menus（角色已过滤）+ 前端注册项合并。
 * 美术资产（extension.theme-asset 消费面）以 CSS 变量注入本壳层，注册/撤销即时
 * 生效、缺省不设变量即平台默认外观（FR-PLUGIN-11）。P12.5：登录后拉取当前生效
 * themeAssets——资产类注册（serve URL 作 path），tokens 类取回 JSON 键值表覆盖
 * --ff-* 设计令牌；主题失败静默降级为基线外观（增强不破壳）。P13 热切换：路由
 * 切换时重拉聚合并差量撤销消失激活（停用/卸载主题免整页刷新）。元数据只作文本
 * 插值与路由跳转（S5）。
 */
const router = useRouter();
const route = useRoute();
const menus = ref<MenuItem[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied'>('loading');
const error = ref<string | null>(null);
const shellTheme = themeStyle();
// P13 热切换竞态守卫：快速连续导航时丢弃陈旧完成（旧响应后到不得复活已撤销主题）
let themeSeq = 0;

async function loadTheme(): Promise<void> {
  const seq = ++themeSeq;
  try {
    const assets = await fetchActiveThemeAssets();
    if (seq !== themeSeq) {
      return;
    }
    syncRemovedActivations(assets.map((asset) => asset.activationId));
    for (const asset of assets) {
      // 单个资产失败只跳过自身（PR #32 审查 P3）：坏包不阻断后续合法主题资产
      try {
        if (asset.kind === 'tokens') {
          // serveUrl 为后端签名 URL：裸 fetch/CSS url() 免 Bearer（PR #32 审查 P1）
          const response = await fetch(asset.serveUrl, {
            headers: { Accept: 'application/json' },
          });
          if (seq !== themeSeq) {
            return;
          }
          if (response.ok) {
            applyTokenOverrides(asset.activationId, await response.json());
          }
        } else {
          registerThemeAsset(
            { key: asset.key, kind: asset.kind, path: asset.serveUrl, scope: asset.scope },
            asset.activationId,
          );
        }
      } catch {
        /* 单资产失败静默跳过 */
      }
    }
  } catch {
    /* 主题是增强能力：聚合拉取失败静默回退平台基线外观 */
  }
}

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
    error.value =
      e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : null;
  }
}

async function signOut(): Promise<void> {
  try {
    await logout();
  } catch {
    /* 登出审计失败不阻塞本地清理（服务端口径：审计降级不阻塞） */
  } finally {
    clearSession();
  }
  await router.push({ path: '/login' });
}

onMounted(async () => {
  // 刷新后恢复会话身份（令牌在 sessionStorage，用户资料从服务端重取）
  if (session.token && !session.user) {
    try {
      session.user = await fetchMe();
    } catch {
      /* 令牌失效由 401 回调统一跳转登录 */
    }
  }
  await loadMenus();
  void loadTheme();
});

// P13 主题热切换：路由切换即重拉聚合（一次轻量 GET；差量撤销在 loadTheme 内）
watch(
  () => route.path,
  () => {
    if (session.token) {
      void loadTheme();
    }
  },
);
</script>

<template>
  <div class="workbench" data-testid="workbench" :style="shellTheme">
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
      <router-view />
    </section>
  </div>
</template>
