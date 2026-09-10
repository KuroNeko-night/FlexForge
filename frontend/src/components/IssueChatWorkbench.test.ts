// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { IssueRecord, SpecRevision } from '@/api/issues';
import IssueChatWorkbench from '@/components/IssueChatWorkbench.vue';

vi.mock('@/api/issues', async () => {
  const actual = await vi.importActual<typeof import('@/api/issues')>('@/api/issues');
  return {
    ...actual,
    listIssues: vi.fn(),
    createIssue: vi.fn(),
    fetchIssue: vi.fn(),
    fetchComments: vi.fn(),
    fetchSpec: vi.fn(),
    publishIssue: vi.fn(),
    addComment: vi.fn(),
  };
});

import {
  createIssue,
  fetchComments,
  fetchIssue,
  fetchSpec,
  listIssues,
  publishIssue,
} from '@/api/issues';

const listMock = vi.mocked(listIssues);
const createMock = vi.mocked(createIssue);
const detailMock = vi.mocked(fetchIssue);
const specMock = vi.mocked(fetchSpec);
const publishMock = vi.mocked(publishIssue);

const issue = (overrides: Partial<IssueRecord> = {}): IssueRecord => ({
  id: 'i1',
  title: '台账需求',
  description: '记录物料台账',
  status: 'SUBMITTED',
  createdBy: 'bob',
  assignedTo: null,
  labels: [],
  createdAt: '2026-09-09T00:00:00Z',
  updatedAt: '2026-09-09T00:00:00Z',
  publishedAt: null,
  ...overrides,
});

const spec = (briefJson: string | null): SpecRevision => ({
  id: 's1',
  issueId: 'i1',
  schemaVersion: 1,
  revision: 1,
  specJson: '{}',
  valid: true,
  validationErrors: null,
  briefJson,
  createdBy: 'ai',
  createdAt: '2026-09-09T00:00:00Z',
});

const briefJson = JSON.stringify({
  colloquial: '你要做台账模块，确认后推送。',
  feasibility: '可直接落地。',
  agentPrompt: '制作 Level 1 插件…',
});

/** clarify 对话桩：点击即广播 specSaved(null, null)（组件内部回落 briefJson 解析）。 */
const chatStub = {
  name: 'IssueClarifyChat',
  props: ['issueId', 'canClarify'],
  emits: ['specSaved'],
  template: '<div data-testid="chat-stub" @click="$emit(\'specSaved\', null, null)" />',
};

function mountWorkbench() {
  return mount(IssueChatWorkbench, {
    global: { stubs: { IssueClarifyChat: chatStub, teleport: true } },
  });
}

describe('IssueChatWorkbench 用户端对话工作台（P23 FR-ISSUE-07）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockResolvedValue([]);
    detailMock.mockResolvedValue(issue());
    vi.mocked(fetchComments).mockResolvedValue([]);
    specMock.mockResolvedValue(spec(briefJson));
  });

  it('新建需求走表单→打开对话视图（审查 P2-2 补齐）', async () => {
    createMock.mockResolvedValue(issue());
    const wrapper = mountWorkbench();
    await flushPromises();
    expect(wrapper.find('[data-testid="new-requirement-form"]').exists()).toBe(true);
    await wrapper.find('[data-testid="new-requirement-title"]').setValue('台账需求');
    await wrapper.find('[data-testid="new-requirement-description"]').setValue('记录物料台账');
    await wrapper.find('[data-testid="start-clarify"]').trigger('submit');
    await flushPromises();
    expect(createMock).toHaveBeenCalledWith({ title: '台账需求', description: '记录物料台账' });
    expect(wrapper.find('[data-testid="confirm-card"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('你要做台账模块，确认后推送。');
  });

  it('成稿后确认推送：publishIssue 成功→已发布徽标+侧栏分组（审查 P2-2 补齐）', async () => {
    const draft = issue();
    const published = issue({ publishedAt: '2026-09-09T01:00:00Z' });
    listMock.mockResolvedValue([draft]);
    publishMock.mockResolvedValue(published);
    const wrapper = mountWorkbench();
    await flushPromises();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="confirm-card"]').exists()).toBe(true);

    await wrapper.find('[data-testid="confirm-publish"]').trigger('click');
    await flushPromises();
    expect(publishMock).toHaveBeenCalledWith('i1');
    expect(wrapper.find('[data-testid="published-badge"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('已发布');
  });
});

describe('IssueChatWorkbench 推送失败路径（P23 续）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockResolvedValue([]);
    detailMock.mockResolvedValue(issue());
    vi.mocked(fetchComments).mockResolvedValue([]);
    specMock.mockResolvedValue(spec(briefJson));
  });

  it('推送失败呈现服务端错误，徽标不出现', async () => {
    listMock.mockResolvedValue([issue()]);
    publishMock.mockRejectedValue(
      new ApiError('validation_error', '尚无有效规格版本', 400, 'req-p23'),
    );
    const wrapper = mountWorkbench();
    await flushPromises();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="confirm-publish"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="published-badge"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('尚无有效规格版本');
  });
});

describe('IssueChatWorkbench 视图状态（P23 续）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockResolvedValue([]);
    detailMock.mockResolvedValue(issue());
    vi.mocked(fetchComments).mockResolvedValue([]);
    specMock.mockResolvedValue(spec(briefJson));
  });

  it('已发布需求不显示确认卡，显示推送状态行与讨论区', async () => {
    const published = issue({ publishedAt: '2026-09-09T01:00:00Z' });
    listMock.mockResolvedValue([published]);
    detailMock.mockResolvedValue(published);
    const wrapper = mountWorkbench();
    await flushPromises();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="confirm-card"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('需求已确认推送');
    expect(wrapper.find('[data-testid="issue-discussion"]').exists()).toBe(true);
  });

  it('侧栏可折叠折叠后仅留展开钮', async () => {
    listMock.mockResolvedValue([issue()]);
    const wrapper = mountWorkbench();
    await flushPromises();
    await wrapper.find('[data-testid="collapse-sidebar"]').trigger('click');
    expect(wrapper.find('[data-testid="expand-sidebar"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="new-requirement"]').exists()).toBe(false);
  });
});

describe('IssueChatWorkbench 切换复位（P24）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listMock.mockResolvedValue([]);
    detailMock.mockResolvedValue(issue());
    vi.mocked(fetchComments).mockResolvedValue([]);
    specMock.mockResolvedValue(spec(briefJson));
  });

  it('切换需求后讨论输入草稿复位（P24：不残留上一需求的评论草稿）', async () => {
    listMock.mockResolvedValue([issue(), issue({ id: 'i2', title: '第二条需求' })]);
    detailMock.mockImplementation((id: string) => Promise.resolve(issue({ id })));
    const wrapper = mountWorkbench();
    await flushPromises();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="comment-input"]').setValue('上一条需求的草稿');
    await wrapper.find('[data-testid="issue-entry-i2"]').trigger('click');
    await flushPromises();
    expect((wrapper.find('[data-testid="comment-input"]').element as HTMLInputElement).value).toBe(
      '',
    );
  });
});
