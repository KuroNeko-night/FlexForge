import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError, apiFetch, onUnauthorized } from '@/api/client';
import { clearSession, saveSession } from '@/auth/token';

function mockFetch(status: number, body: unknown): ReturnType<typeof vi.fn> {
  const impl = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', impl);
  return impl;
}

afterEach(() => {
  vi.unstubAllGlobals();
  clearSession();
});

describe('api client（错误规范化 + 令牌注入 + 401 处理）', () => {
  it('成功响应解析 JSON', async () => {
    mockFetch(200, { value: 42 });
    await expect(apiFetch<{ value: number }>('/demo')).resolves.toEqual({ value: 42 });
  });

  it('错误响应规范化为 ApiError（code/message/requestId）', async () => {
    mockFetch(400, { code: 'validation_error', message: '必填字段缺失: sku', requestId: 'req-1' });
    const error = await apiFetch('/demo').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.code).toBe('validation_error');
    expect(apiError.status).toBe(400);
    expect(apiError.requestId).toBe('req-1');
    expect(apiError.message).toContain('sku');
  });

  it('网络异常归一为 network_error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('offline')));
    const error = (await apiFetch('/demo').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('network_error');
    expect(error.status).toBe(0);
  });

  it('令牌注入 Authorization 头，POST 带 JSON 类型', async () => {
    saveSession('token-1', { id: 1, username: 'u', displayName: 'U', roles: ['USER'] });
    const impl = mockFetch(200, {});
    await apiFetch('/demo', { method: 'POST', body: '{}' });
    const [, init] = impl.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer token-1');
    expect(headers.get('Content-Type')).toBe('application/json');
  });

  it('401 触发全局回调（跳转登录由 main 装配）', async () => {
    const handler = vi.fn();
    onUnauthorized(handler);
    mockFetch(401, { code: 'unauthorized', message: '令牌未提供' });
    await apiFetch('/demo').catch(() => undefined);
    expect(handler).toHaveBeenCalledOnce();
  });
});

describe('api client 空体与 FormData（PR #34 审查 P1 回归）', () => {
  it('200 空体（裸 void 端点）归一为 undefined 不假失败', async () => {
    const impl = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal('fetch', impl);
    await expect(
      apiFetch('/issues/i1/comments', { method: 'POST', body: '{}' }),
    ).resolves.toBeUndefined();
    expect(impl).toHaveBeenCalledOnce();
  });

  it('FormData 体不手工设 Content-Type（浏览器生成 multipart 边界）', async () => {
    const impl = mockFetch(200, {});
    const form = new FormData();
    form.append('file', new File(['zip'], 'p.zip', { type: 'application/zip' }));
    await apiFetch('/plugins/import', { method: 'POST', body: form });
    const [, init] = impl.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Headers).get('Content-Type')).toBeNull();
  });
});
