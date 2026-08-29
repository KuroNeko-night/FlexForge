import { apiFetch } from '@/api/client';

/** 插件清单契约（docs/03 §8 GET /plugins/inventory，ADMIN 只读）。 */
export interface PluginVersionEntry {
  versionId: string;
  version: string;
  createdAt: string;
}

export interface PluginActivationEntry {
  id: string;
  pluginId: string;
  pluginVersionId: string;
  operation: string;
  status: string;
  stage: string | null;
  errorCode: string | null;
  requestedBy: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface PluginInventoryEntry {
  pluginId: string;
  name: string;
  instanceStatus: string;
  versions: PluginVersionEntry[];
  activations: PluginActivationEntry[];
}

/** 插件清单：实例摘要 + 版本 + 激活尝试（含失败阶段与错误码）。 */
export function fetchPluginInventory(): Promise<PluginInventoryEntry[]> {
  return apiFetch<PluginInventoryEntry[]>('/plugins/inventory');
}
