import { apiFetch } from '@/api/client';

/** 当前生效主题资产（GET /plugins/theme-assets，P12.5 换肤/P15 语言包消费面）。 */
export interface ActiveThemeAsset {
  activationId: string;
  pluginId: string;
  key: string;
  kind: 'background' | 'icon' | 'animation' | 'tokens' | 'locale';
  path: string;
  scope: string | null;
  /** 短期 HMAC 签名 URL（CSS url()/裸 fetch 免 Bearer，PR #32 审查 P1 修复）。 */
  serveUrl: string;
}

export function fetchActiveThemeAssets(): Promise<ActiveThemeAsset[]> {
  return apiFetch<ActiveThemeAsset[]>('/plugins/theme-assets');
}
