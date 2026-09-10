import { apiFetch, apiFetchBlob } from '@/api/client';

/**
 * 数据处理器客户端（P20，extension.data-processor 消费面）：清单查询与 invoke；
 * P23 增文件输入（FR-PLUGIN-14）：inputMode=file 处理器经 invoke-file 上传执行，
 * 结果契约增 kind=file（产物经归属校验端点下载）。
 */
export interface ProcessorEntry {
  key: string;
  label: string;
  pluginId: string;
  inputEntity: string;
  inputMode?: 'entity' | 'file';
  accept?: string[];
  maxInputMB?: number;
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
    }
  | {
      /** P23 文件产物：TTL 内可重复下载（artifact_expired 后端 410）。 */
      kind: 'file';
      artifactId: string;
      filename: string;
      sizeBytes: number;
      contentType: string;
      expiresAt: string;
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

/** 文件输入处理器执行（P23）：multipart 上传→受控执行→产物/分析结果。 */
export function invokeFileProcessor(key: string, file: File): Promise<ProcessorResult> {
  const form = new FormData();
  form.append('file', file);
  return apiFetch<ProcessorResult>(`/plugins/processors/${encodeURIComponent(key)}/invoke-file`, {
    method: 'POST',
    body: form,
  });
}

/** 产物下载（P23）：带认证头的 blob 下载（Content-Disposition 由前端落文件名）。 */
export async function downloadProcessorArtifact(
  artifactId: string,
  filename: string,
): Promise<void> {
  const blob = await apiFetchBlob(
    `/plugins/processors/artifacts/${encodeURIComponent(artifactId)}/download`,
  );
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 4000);
}
