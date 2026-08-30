import { apiFetch } from '@/api/client';

/**
 * AI 运行时配置契约（docs/03 §8 GET/PUT /ai/config，ADMIN；FR-SETUP-01）。
 * 读视图只含密钥掩码位——任何接口不回显明文（docs/13 §3.6-5）。
 */
export interface AiConfigView {
  provider: 'fixture' | 'http';
  baseUrl: string;
  model: string;
  apiKeyConfigured: boolean;
  apiKeyHint: string | null;
  apiKeyStale: boolean;
}

export interface UpdateAiConfigPayload {
  provider: 'fixture' | 'http';
  baseUrl?: string | null;
  model?: string | null;
  /** 非空=重设；缺省=保持不变。 */
  apiKey?: string | null;
  clearApiKey?: boolean;
}

export function fetchAiConfig(): Promise<AiConfigView> {
  return apiFetch<AiConfigView>('/ai/config');
}

export function updateAiConfig(payload: UpdateAiConfigPayload): Promise<AiConfigView> {
  return apiFetch<AiConfigView>('/ai/config', {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}
