import { apiFetch } from '@/api/client';

/**
 * 知识库与 AI 助手契约（docs/03 §8 /kb/*，FR-KB-01..04）。
 * 条目读/提问/会话=登录能力；条目写=ADMIN（服务端收口，S2）。
 */

export interface KbEntry {
  id: string;
  title: string;
  category: string | null;
  content: string;
  createdBy: string;
  updatedAt: string;
}

export interface KbReference {
  id: string;
  title: string;
  category: string | null;
}

export interface KbMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  references: KbReference[];
}

export type KbEntryPayload = {
  title: string;
  category?: string | null;
  content: string;
};

export function listKbEntries(): Promise<KbEntry[]> {
  return apiFetch<KbEntry[]>('/kb/entries');
}

export function createKbEntry(payload: KbEntryPayload): Promise<KbEntry> {
  return apiFetch<KbEntry>('/kb/entries', { method: 'POST', body: JSON.stringify(payload) });
}

export function updateKbEntry(id: string, payload: KbEntryPayload): Promise<KbEntry> {
  return apiFetch<KbEntry>(`/kb/entries/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function deleteKbEntry(id: string): Promise<void> {
  return apiFetch<void>(`/kb/entries/${id}`, { method: 'DELETE' });
}

export function fetchKbMessages(): Promise<KbMessage[]> {
  return apiFetch<KbMessage[]>('/kb/messages');
}

export function askKb(question: string): Promise<{ answer: string; references: KbReference[] }> {
  return apiFetch<{ answer: string; references: KbReference[] }>('/kb/ask', {
    method: 'POST',
    body: JSON.stringify({ question }),
  });
}

export function clearKbMessages(): Promise<{ removed: number }> {
  return apiFetch<{ removed: number }>('/kb/messages', { method: 'DELETE' });
}
