import { createRouter, createWebHashHistory } from 'vue-router';

import { session } from '@/auth/token';
import DynamicEntityView from '@/views/DynamicEntityView.vue';
import HomeView from '@/views/HomeView.vue';
import LoginView from '@/views/LoginView.vue';
import PlaceholderView from '@/views/PlaceholderView.vue';
import PluginsView from '@/views/PluginsView.vue';
import WorkbenchView from '@/views/WorkbenchView.vue';

/**
 * 路由（hash 模式：静态托管无需服务端 SPA 回退配置，演示部署最简）。
 * 登录守卫只做体验跳转，安全边界在服务端（S2）。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: WorkbenchView,
      children: [
        { path: '', name: 'home', component: HomeView },
        { path: 'data/:entity', name: 'entity-list', component: DynamicEntityView },
        { path: 'data/:entity/new', name: 'entity-new', component: DynamicEntityView },
        { path: 'data/:entity/:id', name: 'entity-detail', component: DynamicEntityView },
        { path: 'data/:entity/:id/edit', name: 'entity-edit', component: DynamicEntityView },
        {
          path: 'meta/entities',
          name: 'meta-placeholder',
          component: PlaceholderView,
          props: { title: '数据模型' },
        },
        {
          path: 'plugins',
          name: 'plugins',
          component: PluginsView,
        },
        {
          path: 'system',
          name: 'system-placeholder',
          component: PlaceholderView,
          props: { title: '系统管理' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});

router.beforeEach((to) => {
  if (to.name !== 'login' && !session.token) {
    return { name: 'login' };
  }
  return true;
});
