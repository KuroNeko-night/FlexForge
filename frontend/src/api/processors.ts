import { apiFetch } from '@/api/client';

/**
 * 数据处理器客户端（P20，extension.data-processor 消费面）：清单查询与 invoke。
 * 结果契约（后端 OutputValidator 保证）：kind=table（columns/rows）、summary（items）或 chart
 * （chartType/categories/values，P22 FR-PLUGIN-13）。
 */
export interface ProcessorEntry {
  key: string;
  label: string;
  pluginId: string;
  inputEntity: string;
}

export interface ProcessorResultColumn {
  name: string;
  label: string;
}

export type ProcessorResult =
  | { kind: 'table'; columns: ProcessorResultColumn[]; rows: unknown[][] }
  | { kind: 'summary'; items: { label: string; value: string | number | boolean | null }[] }
  | {
      kind: 'chart';
      chartType: 'bar' | 'pie';
      title: string;
      categories: string[];
      values: number[];
    };

export function fetchProcessors(entity?: string): Promise<ProcessorEntry[]> {
  const suffix = entity ? `?entity=${encodeURIComponent(entity)}` : '';
  return apiFetch<ProcessorEntry[]>(`/plugins/processors${suffix}`);
}

export function invokeProcessor(key: string, entity: string): Promise<ProcessorResult> {
  return apiFetch<ProcessorResult>(`/plugins/processors/${encodeURIComponent(key)}/invoke`, {
    method: 'POST',
    body: JSON.stringify({ entity }),
  });
}
