import { describe, expect, it } from 'vitest';

import { clearSession, saveSession } from '@/auth/token';
import {
  mergedMenus,
  registerMenu,
  revokeMenu,
  revokeMenusByActivation,
} from '@/registry/menuRegistry';

describe('菜单 registry（RB-UI：注册/撤销不残留）', () => {
  it('本地贡献与后端菜单合并，排序 order→key', () => {
    clearSession();
    registerMenu({ key: 'local.extra', title: '扩展入口', route: '/data/x', order: 20 });
    const merged = mergedMenus([
      { key: 'workbench', title: '工作台', route: '/', order: 10 },
      { key: 'data-model', title: '数据模型', route: '/meta/entities', order: 20 },
    ]);
    expect(merged.map((menu) => menu.key)).toEqual(['workbench', 'data-model', 'local.extra']);

    revokeMenu('local.extra');
    expect(
      mergedMenus([{ key: 'workbench', title: '工作台', route: '/', order: 10 }]).map((m) => m.key),
    ).toEqual(['workbench']);
  });

  it('同 key 以后端为单一事实源，本地项被压制', () => {
    clearSession();
    registerMenu({ key: 'workbench', title: '本地仿冒', route: '/evil', order: 0 });
    const merged = mergedMenus([{ key: 'workbench', title: '工作台', route: '/', order: 10 }]);
    expect(merged).toHaveLength(1);
    expect(merged[0].title).toBe('工作台');
    revokeMenu('workbench');
  });

  it('permissionKey 本地过滤（服务端授权才是边界，S2）', () => {
    clearSession();
    saveSession('t', {
      id: 1,
      username: 'u',
      displayName: 'U',
      roles: ['USER'],
    });
    registerMenu({
      key: 'local.admin',
      title: '管理入口',
      route: '/x',
      order: 1,
      permissionKey: 'ADMIN',
    });
    registerMenu({ key: 'local.user', title: '用户入口', route: '/y', order: 2 });
    const merged = mergedMenus([]);
    expect(merged.map((menu) => menu.key)).toEqual(['local.user']);
    revokeMenu('local.admin');
    revokeMenu('local.user');
    clearSession();
  });

  it('按 activationId 撤销全部本地贡献（插件停用语义）', () => {
    clearSession();
    registerMenu({ key: 'p.a', title: 'A', route: '/a', order: 1 }, 'act-9');
    registerMenu({ key: 'p.b', title: 'B', route: '/b', order: 2 }, 'act-9');
    expect(revokeMenusByActivation('act-9')).toBe(2);
    expect(mergedMenus([])).toHaveLength(0);
  });
});
