import { apiFetch } from '@/api/client';
import { t } from '@/registry/localeRegistry';

/**
 * Issue/AI 契约（docs/03 §8 /issues，后端 IssueController）。
 * 创建/评论/标签/指派=登录用户；迁移/规格/预览/生成=DEVELOPER；
 * clarify=作者或开发者（FR-ISSUE-01..06 前端消费面，P15）。
 */
export interface IssueRecord {
  id: string;
  title: string;
  description: string;
  status: IssueStatusName;
  createdBy: string;
  assignedTo: string | null;
  labels: string[];
  createdAt: string;
  updatedAt: string;
  /** P23 FR-ISSUE-07：确认推送时间（null=未发布）。 */
  publishedAt: string | null;
}

export type IssueStatusName =
  | 'SUBMITTED'
  | 'APPROVED'
  | 'RETURNED'
  | 'IN_TESTING'
  | 'DEV_FAILED'
  | 'TESTED'
  | 'FEEDBACK'
  | 'DONE'
  | 'CLOSED';

export interface IssueComment {
  id: string;
  issueId: string;
  author: string;
  body: string;
  createdAt: string;
}

export interface SpecRevision {
  id: string;
  issueId: string;
  schemaVersion: number;
  revision: number;
  specJson: string;
  valid: boolean;
  validationErrors: string | null;
  /** P23 FR-ISSUE-03B：三段简报 JSON 快照（v2 及更早版本为 null）。 */
  briefJson: string | null;
  createdBy: string;
  createdAt: string;
}

/** 三段简报（提示词 v3）：口语化确认→用户；可行性/制作提示词→开发者。 */
export interface ClarifyBrief {
  colloquial: string;
  feasibility: string;
  agentPrompt: string;
}

/** clarify 结果：未成规格时给出下一轮追问；成规格时附三段简报（P23 v3）。 */
export interface ClarifyOutcome {
  specProduced: boolean;
  questions: string[];
  spec: SpecRevision | null;
  brief: ClarifyBrief | null;
}

export interface GenerateOutcome {
  pluginId: string;
  version: string;
  versionId: string;
  activationId: string;
  issue: IssueRecord;
}

export interface SpecPreview {
  valid: boolean;
  resources: Record<string, string>;
  errors: string[];
}

export function listIssues(status?: string, page = 1, pageSize = 50): Promise<IssueRecord[]> {
  const query = status
    ? `?status=${status}&page=${page}&pageSize=${pageSize}`
    : `?page=${page}&pageSize=${pageSize}`;
  return apiFetch<IssueRecord[]>(`/issues${query}`);
}

/** 需求工坊（FR-ISSUE-09，P30）：对话式创建，澄清完成后 AI 调用工具建 issue。 */
export interface WorkshopMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  issueId: string | null;
}

export interface WorkshopOutcome {
  reply: string;
  issueId: string | null;
  issueTitle: string | null;
}

export function fetchWorkshopMessages(): Promise<WorkshopMessage[]> {
  return apiFetch<WorkshopMessage[]>('/issues/workshop');
}

export function sendWorkshopMessage(message: string): Promise<WorkshopOutcome> {
  return apiFetch<WorkshopOutcome>('/issues/workshop', {
    method: 'POST',
    body: JSON.stringify({ message }),
  });
}

export function clearWorkshopMessages(): Promise<{ removed: number }> {
  return apiFetch<{ removed: number }>('/issues/workshop', { method: 'DELETE' });
}

export function createIssue(payload: {
  title: string;
  description: string;
  labels?: string[];
}): Promise<IssueRecord> {
  return apiFetch<IssueRecord>('/issues', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function fetchIssue(issueId: string): Promise<IssueRecord> {
  return apiFetch<IssueRecord>(`/issues/${issueId}`);
}

/** 确认并推送（P23 FR-ISSUE-07）：作者本人；门=有效规格+简报齐备；幂等。 */
export function publishIssue(issueId: string): Promise<IssueRecord> {
  return apiFetch<IssueRecord>(`/issues/${issueId}/publish`, { method: 'POST' });
}

export function fetchComments(issueId: string): Promise<IssueComment[]> {
  return apiFetch<IssueComment[]>(`/issues/${issueId}/comments`);
}

export function addComment(issueId: string, body: string): Promise<void> {
  return apiFetch<void>(`/issues/${issueId}/comments`, {
    method: 'POST',
    body: JSON.stringify({ body }),
  });
}

export function replaceLabels(issueId: string, labels: string[]): Promise<IssueRecord> {
  return apiFetch<IssueRecord>(`/issues/${issueId}/labels`, {
    method: 'PATCH',
    body: JSON.stringify({ labels }),
  });
}

/** Issue 状态机镜像（后端 IssueStatus 为唯一权威；此表仅驱动 UI 可选项）。 */
export const ISSUE_TRANSITIONS: Record<IssueStatusName, IssueStatusName[]> = {
  SUBMITTED: ['APPROVED', 'RETURNED'],
  RETURNED: ['SUBMITTED'],
  APPROVED: ['IN_TESTING', 'DEV_FAILED'],
  DEV_FAILED: ['APPROVED'],
  IN_TESTING: ['TESTED', 'FEEDBACK'],
  FEEDBACK: ['APPROVED'],
  TESTED: ['DONE', 'CLOSED'],
  DONE: [],
  CLOSED: [],
};

export const ISSUE_STATUS_LABELS: Record<IssueStatusName, string> = {
  SUBMITTED: '待审核',
  APPROVED: '已批准',
  RETURNED: '退回修改',
  IN_TESTING: '待测试',
  DEV_FAILED: '开发失败',
  TESTED: '测试通过',
  FEEDBACK: '反馈修复',
  DONE: '已完成',
  CLOSED: '已关闭',
};

/** 旁路迁移须填原因（后端 IssueStatus.transitionRequiresReason 同口径）。 */
export function transitionRequiresReason(target: IssueStatusName): boolean {
  return ['RETURNED', 'DEV_FAILED', 'FEEDBACK', 'CLOSED'].includes(target);
}

/**
 * 迁移动作动词（P16 交互规范）：迁移入口按目标状态给按钮，标签用动作而非
 * 状态名——"批准"优于"已批准"；仅驱动 UI，后端仍按状态机校验。
 */
/** 渲染期状态/迁移动作标签（语言包覆盖，缺省=中文基线；P26 多语言）。 */
export function issueStatusLabel(status: IssueStatusName): string {
  return t(`issues.status.${status}`, ISSUE_STATUS_LABELS[status] ?? status);
}

export function transitionLabel(target: IssueStatusName): string {
  return t(`issues.transition.${target}`, TRANSITION_LABELS[target] ?? target);
}

export const TRANSITION_LABELS: Record<IssueStatusName, string> = {
  SUBMITTED: '重新提交',
  APPROVED: '批准',
  RETURNED: '退回修改',
  IN_TESTING: '进入测试',
  DEV_FAILED: '标记开发失败',
  TESTED: '标记测试通过',
  FEEDBACK: '反馈修复',
  DONE: '标记完成',
  CLOSED: '关闭',
};

export function transitionIssue(
  issueId: string,
  to: IssueStatusName,
  reason: string,
): Promise<IssueRecord> {
  return apiFetch<IssueRecord>(`/issues/${issueId}/transition`, {
    method: 'POST',
    body: JSON.stringify({ to, reason }),
  });
}

/** 后端 latestSpec 无规格时返回 200 空体（apiFetch 已归一 undefined），此处收敛为 null。 */
export async function fetchSpec(issueId: string): Promise<SpecRevision | null> {
  return (await apiFetch<SpecRevision>(`/issues/${issueId}/spec`)) ?? null;
}

/** 手工保存规格（FR-ISSUE-06 模型不可用兜底）：请求体即规格 JSON 本身。 */
export function saveSpec(issueId: string, specJson: string): Promise<SpecRevision> {
  return apiFetch<SpecRevision>(`/issues/${issueId}/spec`, {
    method: 'PUT',
    body: specJson,
  });
}

export function fetchPreview(issueId: string): Promise<SpecPreview> {
  return apiFetch<SpecPreview>(`/issues/${issueId}/preview`);
}

export function clarifyIssue(issueId: string, answer?: string): Promise<ClarifyOutcome> {
  return apiFetch<ClarifyOutcome>(`/issues/${issueId}/clarify`, {
    method: 'POST',
    body: JSON.stringify({ answer: answer ?? null }),
  });
}

export function generatePlugin(issueId: string): Promise<GenerateOutcome> {
  return apiFetch<GenerateOutcome>(`/issues/${issueId}/generate`, {
    method: 'POST',
  });
}
