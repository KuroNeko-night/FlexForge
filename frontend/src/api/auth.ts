import { apiFetch } from '@/api/client';
import type { CurrentUser } from '@/auth/token';

interface LoginResponse {
  token: string;
  expiresAt: string;
  user: CurrentUser;
}

/** 登录（用户名/密码 → JWT；后端统一错误防枚举，S3）。 */
export function login(username: string, password: string): Promise<LoginResponse> {
  return apiFetch<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

/** 登出：后端记审计事件，令牌由前端清除（docs/13 §3.1.5）。 */
export function logout(): Promise<void> {
  return apiFetch<void>('/auth/logout', { method: 'POST' });
}

/** 当前用户资料（刷新后恢复会话身份用）。 */
export function fetchMe(): Promise<CurrentUser> {
  return apiFetch<CurrentUser>('/auth/me');
}
