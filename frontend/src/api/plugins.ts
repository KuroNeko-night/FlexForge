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

/** 导入校验报告（ADMIN；docs/03 §8 POST /plugins/validate）。findings 为字符串清单。 */
export interface ValidationReport {
  valid: boolean;
  preview: InstallPreview | null;
  findings: string[];
}

export interface InstallPreview {
  pluginId: string;
  version: string;
  versionId: string;
  contentHash: string;
  isNew: boolean;
  dependencies: { pluginId: string; versionRange: string }[];
}

/** 上传插件包校验（不落库）。 */
export function validatePackage(file: File): Promise<ValidationReport> {
  const form = new FormData();
  form.append('file', file);
  return apiFetch<ValidationReport>('/plugins/validate', { method: 'POST', body: form });
}

/** 上传插件包导入（FR-PLUGIN-01）。 */
export function importPackage(file: File): Promise<InstallPreview> {
  const form = new FormData();
  form.append('file', file);
  return apiFetch<InstallPreview>('/plugins/import', { method: 'POST', body: form });
}

/** 激活指定版本（FR-PLUGIN-02）。 */
export function activateVersion(versionId: string): Promise<PluginActivationEntry> {
  return apiFetch<PluginActivationEntry>(`/plugins/${versionId}/activate`, { method: 'POST' });
}

/** 停用当前激活。 */
export function stopActivation(activationId: string): Promise<PluginActivationEntry> {
  return apiFetch<PluginActivationEntry>(`/plugins/${activationId}/stop`, { method: 'POST' });
}

/** 卸载插件（停用语义+审计保留）。 */
export function uninstallPlugin(pluginId: string): Promise<void> {
  return apiFetch<void>(`/plugins/${pluginId}`, { method: 'DELETE' });
}
