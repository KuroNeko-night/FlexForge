import { apiFetch, apiFetchBlob } from '@/api/client';

/**
 * 知识库与 AI 助手契约（docs/03 §8 /kb/*，FR-KB-01..05）。
 * 条目读/提问/会话=登录能力；条目写=ADMIN（服务端收口，S2）。
 * P29：提问可携带附件（multipart）；附件按消息归属下载（本人）。
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

export interface KbAttachment {
  id: string;
  messageId: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
}

export interface KbMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  references: KbReference[];
  attachments: KbAttachment[];
}

export type KbEntryPayload = {
  title: string;
  category?: string | null;
  content: string;
};

export const KB_ACCEPT = '.png,.jpg,.jpeg,.gif,.webp,.pdf,.csv,.xlsx,.docx,.txt,.md';
export const KB_MAX_FILES = 3;
export const KB_MAX_FILE_BYTES = 10 * 1024 * 1024;

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

export interface KbAskOutcome {
  answer: string;
  references: KbReference[];
  attachments: KbAttachment[];
}

/** 提问（P29：带附件走 multipart，FormData 由浏览器生成边界）。 */
export function askKb(question: string, files: File[] = []): Promise<KbAskOutcome> {
  if (files.length === 0) {
    return apiFetch<KbAskOutcome>('/kb/ask', {
      method: 'POST',
      body: JSON.stringify({ question }),
    });
  }
  const form = new FormData();
  form.append('question', question);
  for (const file of files) {
    form.append('files', file);
  }
  return apiFetch<KbAskOutcome>('/kb/ask', { method: 'POST', body: form });
}

/** 附件 blob（本人；缩略图与下载共用通道）。 */
export function fetchKbAttachmentBlob(attachmentId: string): Promise<Blob> {
  return apiFetchBlob(`/kb/attachments/${encodeURIComponent(attachmentId)}`);
}

/** 触发浏览器下载（Content-Disposition 文件名由 a[download] 覆盖）。 */
export async function downloadKbAttachment(attachment: KbAttachment): Promise<void> {
  const blob = await fetchKbAttachmentBlob(attachment.id);
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = attachment.filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function clearKbMessages(): Promise<{ removed: number }> {
  return apiFetch<{ removed: number }>('/kb/messages', { method: 'DELETE' });
}
