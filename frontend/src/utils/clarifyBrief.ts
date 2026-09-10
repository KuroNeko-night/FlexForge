import type { ClarifyBrief } from '@/api/issues';

/**
 * 三段简报快照解析（P23，FR-ISSUE-03B）：briefJson 来自规格版本行；
 * 非法 JSON 或缺段返回 null（消费方据此隐藏简报面/确认卡）。
 */
export function parseClarifyBrief(briefJson: string | null): ClarifyBrief | null {
  if (!briefJson) {
    return null;
  }
  try {
    const parsed = JSON.parse(briefJson) as Partial<ClarifyBrief>;
    if (!parsed.colloquial || !parsed.feasibility || !parsed.agentPrompt) {
      return null;
    }
    return { ...parsed } as ClarifyBrief;
  } catch {
    return null;
  }
}
