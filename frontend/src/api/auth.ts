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

/** 自助注册（P13，docs/09 P13）：注册成功即登录，响应与登录同构。 */
export function register(
  username: string,
  password: string,
  displayName: string,
): Promise<LoginResponse> {
  return apiFetch<LoginResponse>('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, displayName }),
  });
}

/** 注册入口可见性（feature flag 消费面；失败按关闭处理=隐藏入口）。 */
export function fetchRegistrationStatus(): Promise<{ selfRegistrationEnabled: boolean }> {
  return apiFetch<{ selfRegistrationEnabled: boolean }>('/auth/registration-status');
}

/** 当前用户资料（刷新后恢复会话身份用）。 */
export function fetchMe(): Promise<CurrentUser> {
  return apiFetch<CurrentUser>('/auth/me');
}
