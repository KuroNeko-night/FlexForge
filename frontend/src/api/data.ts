import { apiFetch } from '@/api/client';
import type { PageResult, RecordView } from '@/api/types';

/**
 * 动态数据客户端（service.data-access 的 /api/v1/data 契约）。
 * 过滤参数形如 { '字段.操作符': '值' }，由后端白名单解析（NFR-SEC-02）。
 */
export function queryRecords(
  entity: string,
  params: Record<string, string>,
): Promise<PageResult<RecordView>> {
  const query = new URLSearchParams(params).toString();
  const suffix = query ? `?${query}` : '';
  return apiFetch<PageResult<RecordView>>(`/data/${encodeURIComponent(entity)}${suffix}`);
}

export function fetchRecord(entity: string, id: string): Promise<RecordView> {
  return apiFetch<RecordView>(`/data/${encodeURIComponent(entity)}/${encodeURIComponent(id)}`);
}

export function createRecord(entity: string, data: Record<string, unknown>): Promise<RecordView> {
  return apiFetch<RecordView>(`/data/${encodeURIComponent(entity)}`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export function updateRecord(
  entity: string,
  id: string,
  data: Record<string, unknown>,
): Promise<RecordView> {
  return apiFetch<RecordView>(`/data/${encodeURIComponent(entity)}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(data),
  });
}

export function deleteRecord(entity: string, id: string): Promise<void> {
  return apiFetch<void>(`/data/${encodeURIComponent(entity)}/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
