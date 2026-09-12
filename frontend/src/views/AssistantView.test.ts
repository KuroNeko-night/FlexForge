// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { KbMessage } from '@/api/kb';
import AssistantView from '@/views/AssistantView.vue';

vi.mock('@/api/kb', () => ({
  fetchKbMessages: vi.fn(),
  askKb: vi.fn(),
  clearKbMessages: vi.fn(),
  fetchKbAttachmentBlob: vi.fn(),
  downloadKbAttachment: vi.fn(),
  KB_ACCEPT: '.png,.jpg,.jpeg,.gif,.webp,.pdf,.csv,.xlsx,.docx,.txt,.md',
  KB_MAX_FILES: 3,
  KB_MAX_FILE_BYTES: 10 * 1024 * 1024,
}));
vi.mock('@/api/issues', () => ({
  listIssues: vi.fn().mockResolvedValue([]),
  fetchIssue: vi.fn(),
  fetchComments: vi.fn().mockResolvedValue([]),
  fetchSpec: vi.fn().mockResolvedValue(null),
  createIssue: vi.fn(),
  publishIssue: vi.fn(),
  issueStatusLabel: (s: string) => s,
  transitionLabel: (s: string) => s,
}));

import { askKb, clearKbMessages, fetchKbMessages } from '@/api/kb';

const fetchMock = vi.mocked(fetchKbMessages);
const askMock = vi.mocked(askKb);
const clearMock = vi.mocked(clearKbMessages);

const stubs = { teleport: true };

function mountView() {
  return mount(AssistantView, { global: { stubs } });
}

const historyMessage = (id: string): KbMessage => ({
  id,
  role: 'assistant',
  content: 'a',
  references: [{ id: 'kb-1', title: '条目A', category: null }],
  attachments: [],
});

describe('AssistantView 会话回放与提问流（FR-KB-03）', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    askMock.mockReset();
    clearMock.mockReset();
    globalThis.sessionStorage?.clear();
  });

  it('空会话呈现欢迎提示；历史会话按序回放并显示引用条目', async () => {
    fetchMock.mockResolvedValue([]);
    const empty = mountView();
    await flushPromises();
    expect(empty.find('[data-testid="assistant-log"]').exists()).toBe(false);

    fetchMock.mockResolvedValue([
      { id: 'kcm-1', role: 'user', content: '怎么报销差旅费用', references: [], attachments: [] },
      {
        id: 'kcm-2',
        role: 'assistant',
        content: '根据知识库相关条目…',
        references: [{ id: 'kb-1', title: '差旅报销规范', category: '财务制度' }],
        attachments: [],
      },
    ]);
    const wrapper = mountView();
    await flushPromises();
    const log = wrapper.find('[data-testid="assistant-log"]');
    expect(log.exists()).toBe(true);
    expect(wrapper.findAll('[data-role="user"]')).toHaveLength(1);
    const refs = wrapper.find('[data-testid="assistant-refs"]');
    expect(refs.text()).toContain('差旅报销规范');
    expect(refs.text()).toContain('财务制度');
  });

  it('回答失败回滚乐观消息并内联报错', async () => {
    fetchMock.mockResolvedValue([]);
    askMock.mockRejectedValue(new ApiError('model_unavailable', '模型不可用', 503, null));
    const wrapper = mountView();
    await flushPromises();
    const input = wrapper.find('[data-testid="assistant-input"]');
    await input.setValue('报销流程');
    await wrapper.find('[data-testid="assistant-send"]').trigger('click');
    await flushPromises();
    expect(wrapper.findAll('[data-testid="assistant-log"] .message')).toHaveLength(0);
    expect((input.element as HTMLTextAreaElement).value).toBe('报销流程');
    expect(wrapper.find('[data-testid="assistant-error"]').text()).toContain('模型不可用');
  });
});

describe('AssistantView 提问流与附件回执（FR-KB-05）', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    askMock.mockReset();
    clearMock.mockReset();
    globalThis.sessionStorage?.clear();
  });

  it('提问流：回答与引用追加，服务端附件回执替换待发占位', async () => {
    fetchMock.mockResolvedValue([]);
    askMock.mockResolvedValue({
      answer: '回答正文',
      references: [{ id: 'kb-1', title: '条目A', category: null }],
      attachments: [
        {
          id: 'kba-1',
          messageId: 'kcm-1',
          filename: '报销单.csv',
          contentType: 'text/csv',
          sizeBytes: 24,
        },
      ],
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="assistant-input"]').setValue('怎么报销');
    await wrapper.find('[data-testid="assistant-send"]').trigger('click');
    await flushPromises();
    expect(askMock).toHaveBeenCalledWith('怎么报销', []);
    const messages = wrapper.findAll('[data-testid="assistant-log"] .message');
    expect(messages).toHaveLength(2);
    const files = wrapper.find('[data-testid="assistant-files"]');
    expect(files.text()).toContain('报销单.csv');
    expect(wrapper.find('[data-testid="assistant-refs"]').text()).toContain('条目A');
  });
});

describe('AssistantView 清空与模式切换（FR-KB-04/06）', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    askMock.mockReset();
    clearMock.mockReset();
    globalThis.sessionStorage?.clear();
  });

  it('清空会话经确认对话', async () => {
    fetchMock.mockResolvedValue([
      { id: 'kcm-1', role: 'user', content: 'q', references: [], attachments: [] },
      historyMessage('kcm-2'),
    ]);
    clearMock.mockResolvedValue({ removed: 2 });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="assistant-clear"]').trigger('click');
    expect(wrapper.text()).toContain('清空会话');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(clearMock).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="assistant-log"]').exists()).toBe(false);
  });

  it('滑动开关切换 Issue 模式（内嵌工作台）并记忆会话级模式', async () => {
    fetchMock.mockResolvedValue([]);
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="mode-assistant"]').attributes('aria-selected')).toBe('true');
    await wrapper.find('[data-testid="mode-issues"]').trigger('click');
    expect(wrapper.find('[data-testid="assistant-issues-mode"]').exists()).toBe(true);
    expect(wrapper.findComponent({ name: 'IssueChatWorkbench' }).exists()).toBe(true);
    expect(globalThis.sessionStorage?.getItem('flexforge.assistant.mode')).toBe('issues');
    expect(wrapper.find('h2').text()).toContain('Issue 工作台');
    await wrapper.find('[data-testid="mode-assistant"]').trigger('click');
    expect(wrapper.find('[data-testid="assistant-input"]').exists()).toBe(true);
  });
});
