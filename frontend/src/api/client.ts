import { session } from '@/auth/token';

/**
 * 统一 API 客户端：/api/v1 前缀、Bearer 注入、错误规范化（NFR-UX-01 的 error 反馈源）。
 * 后端错误体 {code,message,requestId}（docs/08 §7）；网络异常归一为 network_error。
 * 401 时触发全局回调（main.ts 注册跳转登录），客户端本身不依赖路由。
 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly requestId: string | null;

  constructor(code: string, message: string, status: number, requestId: string | null) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
    this.requestId = requestId;
  }
}

interface ErrorPayload {
  code?: string;
  message?: string;
  requestId?: string;
}

let unauthorizedHandler: (() => void) | null = null;

/** 注册 401 全局处理（main.ts 装配跳转登录，避免循环依赖）。 */
export function onUnauthorized(handler: () => void): void {
  unauthorizedHandler = handler;
}

function buildHeaders(init?: RequestInit): Headers {
  const headers = new Headers(init?.headers);
  headers.set('Accept', 'application/json');
  if (session.token) {
    headers.set('Authorization', `Bearer ${session.token}`);
  }
  // FormData 由浏览器生成 multipart 边界，手工设 Content-Type 反而破坏上传（P15 插件包）
  if (init?.body != null && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  return headers;
}

async function toApiError(response: Response): Promise<ApiError> {
  const payload = (await response.json().catch(() => ({}))) as ErrorPayload;
  return new ApiError(
    payload.code ?? 'internal_error',
    payload.message ?? `请求失败（HTTP ${response.status}）`,
    response.status,
    payload.requestId ?? null,
  );
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, { ...init, headers: buildHeaders(init) });
  } catch {
    throw new ApiError('network_error', '网络异常，请稍后重试', 0, null);
  }

  if (response.status === 401) {
    unauthorizedHandler?.();
  }
  if (!response.ok) {
    throw await toApiError(response);
  }
  // 204 与 200 空体（裸 void 端点：评论/卸载等）统一归一 undefined；
  // 空 text 先读再判，避免对空体 response.json() 抛 SyntaxError 假失败（PR #34 审查 P1）
  const text = await response.text();
  if (text === '') {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}
