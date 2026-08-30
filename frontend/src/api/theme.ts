import { apiFetch } from '@/api/client';

/** 当前生效主题资产（GET /plugins/theme-assets，P12.5 换肤消费面）。 */
export interface ActiveThemeAsset {
  activationId: string;
  pluginId: string;
  key: string;
  kind: 'background' | 'icon' | 'animation' | 'tokens';
  path: string;
  scope: string | null;
}

export function fetchActiveThemeAssets(): Promise<ActiveThemeAsset[]> {
  return apiFetch<ActiveThemeAsset[]>('/plugins/theme-assets');
}

/** 按 activationId+path 取回主题资产原文（同源 serve 端点；tokens 为 JSON 文本）。 */
export function themeAssetUrl(asset: ActiveThemeAsset): string {
  return `/api/v1/plugins/activations/${asset.activationId}/assets/${asset.path}`;
}
