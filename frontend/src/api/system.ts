import { apiFetch } from '@/api/client';
import type { PageResult } from '@/api/types';

/** 系统用户管理契约（docs/03 §8 /system/users，ADMIN；后端 SystemUserController）。 */
export interface SystemUser {
  id: number;
  username: string;
  displayName: string;
  status: 'ACTIVE' | 'BLOCKED';
  roles: string[];
}

export interface CreateUserPayload {
  username: string;
  password: string;
  displayName: string;
  roles: string[];
}

export function listUsers(page = 1, pageSize = 50): Promise<PageResult<SystemUser>> {
  return apiFetch<PageResult<SystemUser>>(`/system/users?page=${page}&pageSize=${pageSize}`);
}

export function createUser(payload: CreateUserPayload): Promise<SystemUser> {
  return apiFetch<SystemUser>('/system/users', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function assignRoles(userId: number, roles: string[]): Promise<SystemUser> {
  return apiFetch<SystemUser>(`/system/users/${userId}/roles`, {
    method: 'PUT',
    body: JSON.stringify({ roles }),
  });
}

/** 账号停启用（P13）：status ∈ ACTIVE/BLOCKED；后端拒绝操作自己。 */
export function updateStatus(userId: number, status: 'ACTIVE' | 'BLOCKED'): Promise<SystemUser> {
  return apiFetch<SystemUser>(`/system/users/${userId}/status`, {
    method: 'PUT',
    body: JSON.stringify({ status }),
  });
}

/** 批量停启用（P24，FR-AUTH-05）：单请求批量，响应为逐用户终态（顺序与去重后请求一致）。 */
export function batchUpdateStatus(
  userIds: number[],
  status: 'ACTIVE' | 'BLOCKED',
): Promise<SystemUser[]> {
  return apiFetch<SystemUser[]>('/system/users/batch-status', {
    method: 'POST',
    body: JSON.stringify({ userIds, status }),
  });
}

/** 审计事件（P24 前端消费面；后端契约自 P03：result ∈ success/failure）。 */
export interface AuditEventRecord {
  id: string;
  actor: string;
  action: string;
  objectId: string;
  result: string;
  occurredAt: string;
}

export interface AuditQueryParams {
  page?: number;
  pageSize?: number;
  actor?: string;
  action?: string;
  objectId?: string;
}

/** 审计事件查询（ADMIN）：过滤项全可选，空值不下发参数。 */
export function queryAuditEvents(params: AuditQueryParams): Promise<PageResult<AuditEventRecord>> {
  const search = new URLSearchParams();
  if (params.page !== undefined) {
    search.set('page', String(params.page));
  }
  if (params.pageSize !== undefined) {
    search.set('pageSize', String(params.pageSize));
  }
  for (const key of ['actor', 'action', 'objectId'] as const) {
    const value = params[key]?.trim();
    if (value) {
      search.set(key, value);
    }
  }
  return apiFetch<PageResult<AuditEventRecord>>(`/system/audit-events?${search.toString()}`);
}
