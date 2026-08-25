import { hasRole } from '@/auth/token';
import type { MenuItem } from '@/api/types';
import { KeyedRegistry } from '@/registry/keyed';

/**
 * 菜单 registry（extension.navigation 前端侧）：后端 /menus（已按角色过滤并
 * 合并插件导航贡献）为基准面，前端注册项叠加（P06 阶段供本地扩展与测试，
 * P08 起插件贡献由后端菜单接口统一下发）。合并去重（同 key 后端优先）、
 * 按 order+key 排序；撤销（close/revoke/revokeByActivation）后不残留入口。
 */
export interface MenuContribution extends MenuItem {
  order: number;
}

const registry = new KeyedRegistry<MenuContribution>();

export function registerMenu(contribution: MenuContribution, activationId: string | null = null) {
  return registry.register(contribution.key, contribution, activationId);
}

export function revokeMenu(key: string): void {
  registry.revoke(key);
}

export function revokeMenusByActivation(activationId: string): number {
  return registry.revokeByActivation(activationId);
}

/** 合并后端菜单与本地注册项：同 key 以后端为准（单一事实源），排序 order→key。 */
export function mergedMenus(serverMenus: MenuItem[]): MenuItem[] {
  const serverKeys = new Set(serverMenus.map((menu) => menu.key));
  const local = registry
    .list()
    .map((entry) => entry.value)
    .filter((menu) => !serverKeys.has(menu.key))
    .filter((menu) => !menu.permissionKey || hasRole(menu.permissionKey));
  return [...serverMenus, ...local].sort(
    (a, b) => (a.order ?? 0) - (b.order ?? 0) || a.key.localeCompare(b.key),
  );
}
