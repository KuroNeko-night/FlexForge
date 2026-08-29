import { apiFetch } from '@/api/client';
import type { EntityDetail, EntitySummary, PageResult } from '@/api/types';

/** 已启用实体列表（普通用户视角；开发者另见 /meta/entities 全量——管理页 P06 不做）。 */
export function listEnabledEntities(): Promise<EntitySummary[]> {
  // pageSize=200 即后端分页硬上限（ApiConstants.MAX_PAGE_SIZE）：一次取全，
  // 实体数超上限会被静默截断（MVP 实体规模假设远小于此）
  return apiFetch<PageResult<EntitySummary>>('/meta/entities?page=1&pageSize=200').then(
    (page) => page.items,
  );
}

/** 实体完整定义（含字段/视图与 metaVersion，动态页面渲染的唯一元数据源）。 */
export function fetchEntity(name: string): Promise<EntityDetail> {
  return apiFetch<EntityDetail>(`/meta/entities/by-name/${encodeURIComponent(name)}`);
}

/** 全量菜单（后端已按角色过滤并合并 extension.navigation 贡献）。 */
export function fetchMenus(): Promise<import('@/api/types').MenuItem[]> {
  return apiFetch<import('@/api/types').MenuItem[]>('/menus');
}
