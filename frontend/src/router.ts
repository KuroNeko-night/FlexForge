import { createRouter, createWebHashHistory } from 'vue-router';

import { session } from '@/auth/token';
import AuditView from '@/views/AuditView.vue';
import DynamicEntityView from '@/views/DynamicEntityView.vue';
import HomeView from '@/views/HomeView.vue';
import IssuesView from '@/views/IssuesView.vue';
import LoginView from '@/views/LoginView.vue';
import SettingsView from '@/views/SettingsView.vue';
import PlaceholderView from '@/views/PlaceholderView.vue';
import PluginsView from '@/views/PluginsView.vue';
import ToolsView from '@/views/ToolsView.vue';
import UsersView from '@/views/UsersView.vue';
import WorkbenchView from '@/views/WorkbenchView.vue';

/**
 * 路由（hash 模式：静态托管无需服务端 SPA 回退配置，演示部署最简）。
 * 登录守卫只做体验跳转，安全边界在服务端（S2）。
 * 路径与后端菜单契约对齐（docs/03 §8：/workbench、/meta/entities、/system/users；
 * P12.5 缺陷②：缺失路径曾落 SPA 兜底重定向回工作台）。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: WorkbenchView,
      children: [
        /* home 落在 /workbench 实路径（P16）：菜单链接与路由可匹配，导航激活态
           才生效——原 ''→redirect 结构使 URL 回落 '/'，router-link-active 永不命中 */
        { path: '', redirect: { name: 'home' } },
        { path: 'workbench', name: 'home', component: HomeView },
        { path: 'issues', name: 'issues', component: IssuesView },
        { path: 'tools', name: 'file-tools', component: ToolsView },
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
          redirect: { name: 'system-users' },
        },
        {
          path: 'system/users',
          name: 'system-users',
          component: UsersView,
        },
        {
          path: 'system/audit',
          name: 'system-audit',
          component: AuditView,
        },
        {
          path: 'settings',
          name: 'settings',
          component: SettingsView,
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
