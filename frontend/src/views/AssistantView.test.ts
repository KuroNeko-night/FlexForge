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
}));

import { askKb, clearKbMessages, fetchKbMessages } from '@/api/kb';

const fetchMock = vi.mocked(fetchKbMessages);
const askMock = vi.mocked(askKb);
const clearMock = vi.mocked(clearKbMessages);

const stubs = { teleport: true };

function mountView() {
  return mount(AssistantView, { global: { stubs } });
}

function resetMocks(): void {
  fetchMock.mockReset();
  askMock.mockReset();
  clearMock.mockReset();
}

describe('AssistantView 会话回放与提问流（P28，FR-KB-03）', () => {
  beforeEach(resetMocks);

  it('空会话呈现欢迎提示；历史会话按序回放并显示引用条目', async () => {
    fetchMock.mockResolvedValue([]);
    const empty = mountView();
    await flushPromises();
    expect(empty.find('[data-testid="assistant-log"]').exists()).toBe(false);

    const history: KbMessage[] = [
      { id: 'kcm-1', role: 'user', content: '怎么报销差旅费用', references: [] },
      {
        id: 'kcm-2',
        role: 'assistant',
        content: '根据知识库相关条目…',
        references: [{ id: 'kb-1', title: '差旅报销规范', category: '财务制度' }],
      },
    ];
    fetchMock.mockResolvedValue(history);
    const wrapper = mountView();
    await flushPromises();
    const log = wrapper.find('[data-testid="assistant-log"]');
    expect(log.exists()).toBe(true);
    expect(wrapper.findAll('[data-role="user"]')).toHaveLength(1);
    const refs = wrapper.find('[data-testid="assistant-refs"]');
    expect(refs.text()).toContain('差旅报销规范');
    expect(refs.text()).toContain('财务制度');
  });

  it('提问流：乐观插入→回答与引用追加→输入清空', async () => {
    fetchMock.mockResolvedValue([]);
    askMock.mockResolvedValue({
      answer: '回答正文',
      references: [{ id: 'kb-1', title: '条目A', category: null }],
    });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="assistant-input"]').setValue('怎么报销');
    await wrapper.find('[data-testid="assistant-send"]').trigger('click');
    await flushPromises();
    expect(askMock).toHaveBeenCalledWith('怎么报销');
    const messages = wrapper.findAll('[data-testid="assistant-log"] .message');
    expect(messages).toHaveLength(2);
    expect(wrapper.find('[data-testid="assistant-input"]').element.textContent).toBe('');
    expect(wrapper.find('[data-testid="assistant-refs"]').text()).toContain('条目A');
  });
});

describe('AssistantView 失败路径与清空会话（P28，FR-KB-04）', () => {
  beforeEach(resetMocks);

  it('回答失败回滚乐观消息、保留输入并内联报错', async () => {
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

  it('清空会话经确认对话且失败内联报错', async () => {
    const history: KbMessage[] = [
      { id: 'kcm-1', role: 'user', content: 'q', references: [] },
      { id: 'kcm-2', role: 'assistant', content: 'a', references: [] },
    ];
    fetchMock.mockResolvedValue(history);
    clearMock.mockResolvedValue({ removed: 2 });
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="assistant-clear"]').trigger('click');
    expect(wrapper.text()).toContain('清空会话');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(clearMock).toHaveBeenCalled();
    expect(wrapper.find('[data-testid="assistant-log"]').exists()).toBe(false);

    clearMock.mockReset();
    clearMock.mockRejectedValue(new ApiError('internal_error', '失败', 500, null));
    fetchMock.mockResolvedValue(history);
    const again = mountView();
    await flushPromises();
    await again.find('[data-testid="assistant-clear"]').trigger('click');
    await again.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(again.find('[data-testid="assistant-error"]').exists()).toBe(true);
  });
});
