export interface HealthState {
  status: 'UP' | 'DOWN';
}

interface HealthPayload {
  status?: string;
}

/**
 * 通过 Vite 同源代理读取后端健康状态（docs/13 §3.8 CORS 基线）。
 * 网络异常由调用方捕获；本函数只负责协议转换。
 */
export async function fetchHealth(signal?: AbortSignal): Promise<HealthState> {
  const response = await fetch('/actuator/health', {
    signal,
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    return { status: 'DOWN' };
  }
  const payload = (await response.json()) as HealthPayload;
  return { status: payload.status === 'UP' ? 'UP' : 'DOWN' };
}
