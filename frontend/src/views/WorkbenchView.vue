<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { logout, fetchMe } from '@/api/auth';
import { ApiError, apiErrorMessage } from '@/api/client';
import { fetchMenus } from '@/api/meta';
import { fetchActiveThemeAssets, type ActiveThemeAsset } from '@/api/theme';
import type { MenuItem } from '@/api/types';
import { clearSession, session } from '@/auth/token';
import AppIcon from '@/components/AppIcon.vue';
import AppLogo from '@/components/AppLogo.vue';
import StateView from '@/components/StateView.vue';
import { registerLocalePack, syncRemovedLocalePacks, t } from '@/registry/localeRegistry';
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
 * --ff-* 设计令牌；P15：locale 类取回 {lang,messages} 注册语言包（FR-SETUP-02，
 * 菜单标题经 t() 可被语言包覆盖）；主题失败静默降级为基线外观（增强不破壳）。
 * P13 热切换：路由切换时重拉聚合并差量撤销消失激活（停用/卸载主题免整页刷新）。
 * 元数据只作文本插值与路由跳转（S5）。
 */
const router = useRouter();
const route = useRoute();
const menus = ref<MenuItem[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied'>('loading');
const error = ref<string | null>(null);
const shellTheme = themeStyle();
// P13 热切换竞态守卫：快速连续导航时丢弃陈旧完成（旧响应后到不得复活已撤销主题）
let themeSeq = 0;

/** 签名 URL 取回 JSON 文档（tokens/locale 共用通道）。 */
async function fetchJsonDocument(serveUrl: string): Promise<unknown | null> {
  const response = await fetch(serveUrl, { headers: { Accept: 'application/json' } });
  return response.ok ? response.json() : null;
}

/** 应用单个主题资产（tokens/locale 为 JSON 文档通道，其余为资产注册）。 */
async function applyThemeAsset(asset: ActiveThemeAsset, seq: number): Promise<void> {
  if (asset.kind !== 'tokens' && asset.kind !== 'locale') {
    registerThemeAsset(
      { key: asset.key, kind: asset.kind, path: asset.serveUrl, scope: asset.scope },
      asset.activationId,
    );
    return;
  }
  const doc = (await fetchJsonDocument(asset.serveUrl)) as Record<string, unknown> | null;
  if (seq !== themeSeq) {
    return;
  }
  if (asset.kind === 'tokens' && doc) {
    applyTokenOverrides(asset.activationId, doc);
  } else if (asset.kind === 'locale' && doc && isValidLocaleDoc(doc)) {
    registerLocalePack(asset.activationId, {
      lang: doc.lang as string,
      messages: doc.messages as Record<string, string>,
    });
  }
}

function isValidLocaleDoc(doc: Record<string, unknown> | null): boolean {
  return (
    doc !== null &&
    typeof doc.lang === 'string' &&
    typeof doc.messages === 'object' &&
    doc.messages !== null
  );
}

async function loadTheme(): Promise<void> {
  const seq = ++themeSeq;
  try {
    const assets = await fetchActiveThemeAssets();
    if (seq !== themeSeq) {
      return;
    }
    const activeIds = assets.map((asset) => asset.activationId);
    syncRemovedActivations(activeIds);
    syncRemovedLocalePacks(activeIds);
    for (const asset of assets) {
      // 单个资产失败只跳过自身（PR #32 审查 P3）：坏包不阻断后续合法主题资产
      try {
        await applyThemeAsset(asset, seq);
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
    error.value = apiErrorMessage(e, null);
  }
}

/** 后端 icon 契约值（docs/03 §5 /menus）→ 平台图标名。 */
const MENU_ICON_NAMES: Record<string, string> = {
  dashboard: 'grid',
  database: 'database',
  settings: 'sliders',
};

/** 菜单图标（P16）：优先消费后端 icon 契约字段，未下发时按 key/路由兜底。 */
function iconFor(menu: MenuItem): string {
  const declared = menu.icon ? MENU_ICON_NAMES[menu.icon] : undefined;
  if (declared) {
    return declared;
  }
  if (menu.route?.startsWith('/data/')) {
    return 'layers';
  }
  if (menu.key.includes('issues')) {
    return 'chat';
  }
  if (menu.key.includes('plugins')) {
    return 'package';
  }
  if (menu.key.includes('settings')) {
    return 'sliders';
  }
  if (menu.key.includes('system')) {
    return 'users';
  }
  if (menu.key.includes('data')) {
    return 'database';
  }
  return 'grid';
}

/** 导航分组（P16）：业务实体入口与平台管理分区呈现，组内保持 order 序。 */
const groupedMenus = computed(() => {
  const apps = menus.value.filter((menu) => menu.route?.startsWith('/data/'));
  const platform = menus.value.filter((menu) => !menu.route?.startsWith('/data/'));
  return [
    { key: 'platform', title: t('menu.groupPlatform', '平台'), items: platform },
    { key: 'applications', title: t('menu.groupApplications', '业务应用'), items: apps },
  ].filter((group) => group.items.length > 0);
});

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
      <p class="brand">
        <AppLogo class="brand-mark" />
        <span>FlexForge</span>
      </p>
      <StateView v-if="state !== 'ready'" :state="state" :message="error" />
      <nav v-else aria-label="主导航">
        <section v-for="group in groupedMenus" :key="group.key">
          <p class="side-group-title">{{ group.title }}</p>
          <ul>
            <li v-for="menu in group.items" :key="menu.key">
              <router-link v-if="menu.route" :to="menu.route">
                <AppIcon :name="iconFor(menu)" />
                <span>{{ t(`menu.${menu.key}`, menu.title) }}</span>
              </router-link>
              <span v-else class="menu-static">
                <AppIcon :name="iconFor(menu)" />
                <span>{{ t(`menu.${menu.key}`, menu.title) }}</span>
              </span>
            </li>
          </ul>
        </section>
      </nav>
      <div class="side-footer">
        <span class="who">{{ session.user?.displayName ?? session.user?.username }}</span>
        <button type="button" @click="signOut">{{ t('shell.logout', '登出') }}</button>
      </div>
    </aside>
    <section class="workbench-main">
      <!-- P19 路由过渡：out-in 淡切（时长走令牌，reduced-motion 即时切换）；
           同组件参数导航（如实体列表↔详情）不触发重建，分页/呈现状态保留 -->
      <router-view v-slot="{ Component }">
        <Transition name="page-fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </router-view>
    </section>
  </div>
</template>
