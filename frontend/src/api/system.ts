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
