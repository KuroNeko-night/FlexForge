import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchHealth } from './health';

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, json: async () => body } as unknown as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('fetchHealth', () => {
  it('把后端 UP 映射为 UP', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ status: 'UP' })));

    await expect(fetchHealth()).resolves.toEqual({ status: 'UP' });
  });

  it('非 2xx 响应映射为 DOWN', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ status: 'UP' }, false)));

    await expect(fetchHealth()).resolves.toEqual({ status: 'DOWN' });
  });

  it('非 UP 载荷映射为 DOWN', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ status: 'DOWN' })));

    await expect(fetchHealth()).resolves.toEqual({ status: 'DOWN' });
  });
});
