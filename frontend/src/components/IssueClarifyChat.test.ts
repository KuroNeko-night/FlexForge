// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ClarifyOutcome, SpecRevision } from '@/api/issues';
import IssueClarifyChat from '@/components/IssueClarifyChat.vue';

vi.mock('@/api/issues', async () => {
  const actual = await vi.importActual<typeof import('@/api/issues')>('@/api/issues');
  return {
    ...actual,
    clarifyIssue: vi.fn(),
  };
});

import { clarifyIssue } from '@/api/issues';

const clarifyMock = vi.mocked(clarifyIssue);

const spec: SpecRevision = {
  id: 's1',
  issueId: 'i1',
  schemaVersion: 1,
  revision: 1,
  specJson: '{"schemaVersion":1}',
  valid: true,
  validationErrors: null,
  briefJson: null,
  createdBy: 'ai',
  createdAt: '2026-08-30T02:01:00Z',
};

function outcome(partial: Partial<ClarifyOutcome>): ClarifyOutcome {
  return { specProduced: false, questions: [], spec: null, brief: null, ...partial };
}

function mountChat(canClarify = true) {
  return mount(IssueClarifyChat, {
    props: { issueId: 'i1', canClarify },
    global: { stubs: { teleport: true } },
  });
}

async function start(wrapper: ReturnType<typeof mountChat>): Promise<void> {
  await wrapper.find('[data-testid="clarify-start"]').trigger('click');
  await flushPromises();
}

describe('IssueClarifyChat 现代对话面（P21）', () => {
  beforeEach(() => {
    clarifyMock.mockReset();
  });

  it('等待模型期间显示打字指示，返回后消失且追问成泡', async () => {
    let resolveCall: (value: ClarifyOutcome) => void = () => {};
    clarifyMock.mockReturnValue(
      new Promise<ClarifyOutcome>((resolve) => {
        resolveCall = resolve;
      }),
    );
    const wrapper = mountChat();
    await wrapper.find('[data-testid="clarify-start"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="clarify-typing"]').exists()).toBe(true);
    resolveCall(outcome({ questions: ['实体叫什么？'] }));
    await flushPromises();
    expect(wrapper.find('[data-testid="clarify-typing"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="clarify-log"]').text()).toContain('实体叫什么？');
  });

  it('分角色气泡：AI 与用户消息各占一侧并带角色标签', async () => {
    clarifyMock.mockResolvedValueOnce(outcome({ questions: ['实体叫什么？'] }));
    const wrapper = mountChat();
    await start(wrapper);
    await wrapper.find('[data-testid="clarify-input"]').setValue('物料档案');
    await wrapper.find('[data-testid="clarify-send"]').trigger('click');
    await flushPromises();
    const messages = wrapper.findAll('[data-testid="clarify-log"] .message');
    expect(messages.some((m) => m.attributes('data-role') === 'ai')).toBe(true);
    expect(messages.some((m) => m.attributes('data-role') === 'user')).toBe(true);
    expect(messages.some((m) => m.text().includes('物料档案'))).toBe(true);
  });

  it('IME 组态回车不提交，Shift+Enter 不提交（组词/换行语义）', async () => {
    clarifyMock.mockResolvedValueOnce(outcome({ questions: ['Q1'] }));
    const wrapper = mountChat();
    await start(wrapper);
    const input = wrapper.find('[data-testid="clarify-input"]');
    await input.setValue('答');
    await input.trigger('keydown', { key: 'Enter', isComposing: true });
    await input.trigger('keydown', { key: 'Enter', shiftKey: true });
    await flushPromises();
    expect(clarifyMock).toHaveBeenCalledTimes(1);
  });
});

describe('IssueClarifyChat 规格产出与权限口径', () => {
  beforeEach(() => {
    clarifyMock.mockReset();
  });

  it('规格产出：specSaved 上抛父层并落提示语', async () => {
    clarifyMock.mockResolvedValueOnce(outcome({ specProduced: true, spec }));
    const wrapper = mountChat();
    await start(wrapper);
    expect(wrapper.emitted('specSaved')?.[0]?.[0]).toMatchObject({ revision: 1 });
    expect(wrapper.find('[data-testid="clarify-log"]').text()).toContain('规格草稿');
  });

  it('无 clarify 权限时只显示提示（后端同口径）', () => {
    const wrapper = mountChat(false);
    expect(wrapper.find('[data-testid="clarify-start"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('AI 澄清由 Issue 作者或开发者发起');
  });
});
