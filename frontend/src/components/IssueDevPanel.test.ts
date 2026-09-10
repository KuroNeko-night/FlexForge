// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { IssueRecord, IssueStatusName, SpecRevision } from '@/api/issues';
import IssueDevPanel from '@/components/IssueDevPanel.vue';

vi.mock('@/api/issues', async () => {
  const actual = await vi.importActual<typeof import('@/api/issues')>('@/api/issues');
  return {
    ...actual,
    transitionIssue: vi.fn(),
    saveSpec: vi.fn(),
    fetchPreview: vi.fn(),
    generatePlugin: vi.fn(),
  };
});

import { generatePlugin, saveSpec, transitionIssue } from '@/api/issues';

const transitionMock = vi.mocked(transitionIssue);
const saveSpecMock = vi.mocked(saveSpec);
const generateMock = vi.mocked(generatePlugin);

const baseIssue: IssueRecord = {
  id: 'i1',
  title: '物料需求管理',
  description: '需要物料档案',
  status: 'SUBMITTED',
  createdBy: 'alice',
  assignedTo: null,
  labels: [],
  createdAt: '2026-08-30T02:00:00Z',
  updatedAt: '2026-08-30T02:00:00Z',
  publishedAt: null,
};

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

function mountPanel(status: IssueStatusName = 'SUBMITTED', withSpec = false) {
  return mount(IssueDevPanel, {
    props: { issue: { ...baseIssue, status }, spec: withSpec ? spec : null },
    global: { stubs: { teleport: true } },
  });
}

beforeEach(() => {
  transitionMock.mockReset();
  saveSpecMock.mockReset();
  generateMock.mockReset();
});

describe('IssueDevPanel 状态迁移（FR-ISSUE-02，P16 按钮组）', () => {
  it('主链迁移一键直达：点批准即调用并上抛 updated', async () => {
    transitionMock.mockResolvedValue({ ...baseIssue, status: 'APPROVED' });
    const wrapper = mountPanel('SUBMITTED');
    await flushPromises();
    await wrapper.find('[data-testid="transition-APPROVED"]').trigger('click');
    await flushPromises();
    expect(transitionIssue).toHaveBeenCalledWith('i1', 'APPROVED', '');
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ status: 'APPROVED' });
  });

  it('终态无迁移按钮', async () => {
    const wrapper = mountPanel('DONE');
    await flushPromises();
    expect(wrapper.text()).toContain('当前为终态。');
    expect(wrapper.find('button[data-testid^="transition-"]').exists()).toBe(false);
  });
});

describe('IssueDevPanel 旁路迁移与复位（审查 P2-3/P2-5）', () => {
  it('旁路迁移在确认框内填原因：空原因禁用、填后调用', async () => {
    transitionMock.mockResolvedValue({ ...baseIssue, status: 'RETURNED' });
    const wrapper = mountPanel('SUBMITTED');
    await flushPromises();
    await wrapper.find('[data-testid="transition-RETURNED"]').trigger('click');
    await flushPromises();
    const reason = wrapper.find('[data-testid="confirm-reason"]');
    expect(reason.exists()).toBe(true);
    const submit = wrapper.find('[data-testid="confirm-submit"]').element as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await reason.setValue('需求描述不完整');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(transitionIssue).toHaveBeenCalledWith('i1', 'RETURNED', '需求描述不完整');
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(false);
  });

  it('旁路迁移失败：关闭对话框且错误显示在迁移区', async () => {
    transitionMock.mockRejectedValue(new Error('boom'));
    const wrapper = mountPanel('SUBMITTED');
    await flushPromises();
    await wrapper.find('[data-testid="transition-RETURNED"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="confirm-reason"]').setValue('需求不完整');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="transition-section"]').text()).toContain('迁移失败');
  });

  it('切换 Issue 复位打开中的确认对话框（审查 P2-3）', async () => {
    const wrapper = mountPanel('SUBMITTED');
    await flushPromises();
    await wrapper.find('[data-testid="transition-RETURNED"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(true);
    await wrapper.setProps({ issue: { ...baseIssue, id: 'i2', title: 'B' } });
    await flushPromises();
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(false);
    expect(transitionIssue).not.toHaveBeenCalled();
  });
});

describe('IssueDevPanel 生成（FR-ISSUE-05，P16 统一确认）', () => {
  it('生成须确认：对话框取消不调用', async () => {
    const wrapper = mountPanel('APPROVED');
    await flushPromises();
    const trigger = wrapper.findAll('button').find((b) => b.text().includes('生成并激活'))!;
    await trigger.trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(true);
    await wrapper
      .findAll('button')
      .filter((b) => b.text() === '取消')
      .at(-1)!
      .trigger('click');
    await flushPromises();
    expect(generatePlugin).not.toHaveBeenCalled();
  });

  it('生成确认后调用并展示结果（FR-ISSUE-05）', async () => {
    const wrapper = mountPanel('APPROVED');
    await flushPromises();
    generateMock.mockResolvedValue({
      pluginId: 'gen.iabc',
      version: '0.1.1',
      versionId: 'v2',
      activationId: 'a9',
      issue: { ...baseIssue, status: 'IN_TESTING' },
    });
    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('生成并激活'))!
      .trigger('click');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(generatePlugin).toHaveBeenCalledWith('i1');
    const outcome = wrapper.find('[data-testid="generate-outcome"]').text();
    expect(outcome).toContain('gen.iabc');
    expect(outcome).toContain('0.1.1');
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ status: 'IN_TESTING' });
  });
});
