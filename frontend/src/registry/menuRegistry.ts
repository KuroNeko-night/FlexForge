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
  /** 多角色显隐（P29）：数组非空时命中任意一个即显示；与 permissionKey 同为
   * 体验层（服务端接口为边界）。单角色沿用 MenuItem.permissionKey。 */
  permissionKeys?: string[];
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

/** 角色可见性：permissionKey（单角色）与 permissionKeys（多角色，任一命中）。 */
function visibleToCurrentRoles(menu: MenuContribution): boolean {
  if (menu.permissionKey && !hasRole(menu.permissionKey)) {
    return false;
  }
  if (
    menu.permissionKeys &&
    menu.permissionKeys.length > 0 &&
    !menu.permissionKeys.some((role) => hasRole(role))
  ) {
    return false;
  }
  return true;
}

/** 合并后端菜单与本地注册项：同 key 以后端为准（单一事实源），排序 order→key。 */
export function mergedMenus(serverMenus: MenuItem[]): MenuItem[] {
  const serverKeys = new Set(serverMenus.map((menu) => menu.key));
  const local = registry
    .list()
    .map((entry) => entry.value)
    .filter((menu) => !serverKeys.has(menu.key))
    .filter(visibleToCurrentRoles);
  return [...serverMenus, ...local].sort(
    (a, b) => (a.order ?? 0) - (b.order ?? 0) || a.key.localeCompare(b.key),
  );
}
