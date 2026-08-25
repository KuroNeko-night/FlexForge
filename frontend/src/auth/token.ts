import { reactive } from 'vue';

/**
 * 会话令牌与当前用户（docs/13 §3.1.5：登出=删除令牌+审计，MVP 无服务端吊销）。
 * 令牌存 sessionStorage：浏览器刷新（同标签页）后仍在，关闭标签页即清除；
 * 不写 localStorage 以缩小残留面。前端无密钥（S4），令牌只进请求头不进日志。
 */
export interface CurrentUser {
  id: number;
  username: string;
  displayName: string;
  roles: string[];
}

const TOKEN_KEY = 'flexforge.token';

function readToken(): string | null {
  try {
    return globalThis.sessionStorage?.getItem(TOKEN_KEY) ?? null;
  } catch {
    return null;
  }
}

export const session = reactive({
  token: readToken(),
  user: null as CurrentUser | null,
});

export function saveSession(token: string, user: CurrentUser): void {
  session.token = token;
  session.user = user;
  try {
    globalThis.sessionStorage?.setItem(TOKEN_KEY, token);
  } catch {
    /* 存储不可用时令牌仅存内存，刷新后需重新登录 */
  }
}

export function clearSession(): void {
  session.token = null;
  session.user = null;
  try {
    globalThis.sessionStorage?.removeItem(TOKEN_KEY);
  } catch {
    /* 同上 */
  }
}

export function hasRole(role: string): boolean {
  return session.user?.roles.includes(role) ?? false;
}
