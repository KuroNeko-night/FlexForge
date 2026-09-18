// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { IssueRecord } from '@/api/issues';
import IssueWorkshopView from '@/components/IssueWorkshopView.vue';

vi.mock('@/api/issues', async () => {
  const actual = await vi.importActual<typeof import('@/api/issues')>('@/api/issues');
  return {
    ...actual,
    listIssues: vi.fn(),
    fetchIssue: vi.fn(),
    fetchComments: vi.fn().mockResolvedValue([]),
    fetchSpec: vi.fn().mockResolvedValue(null),
    publishIssue: vi.fn(),
    fetchWorkshopMessages: vi.fn(),
    sendWorkshopMessage: vi.fn(),
    clearWorkshopMessages: vi.fn().mockResolvedValue({ removed: 0 }),
  };
});

import {
  fetchIssue,
  fetchWorkshopMessages,
  listIssues,
  publishIssue,
  sendWorkshopMessage,
} from '@/api/issues';

const listMock = vi.mocked(listIssues);
const detailMock = vi.mocked(fetchIssue);
const workshopMock = vi.mocked(fetchWorkshopMessages);
const sendMock = vi.mocked(sendWorkshopMessage);
const publishMock = vi.mocked(publishIssue);

const stubs = { teleport: true };

const issue = (overrides: Partial<IssueRecord> = {}): IssueRecord => ({
  id: 'i1',
  title: '设备点检管理',
  description: '点检记录管理',
  status: 'SUBMITTED',
  createdBy: 'bob',
  assignedTo: null,
  labels: [],
  createdAt: '2026-09-12T02:00:00Z',
  updatedAt: '2026-09-12T02:00:00Z',
  publishedAt: null,
  ...overrides,
});

function resetMocks(): void {
  listMock.mockReset();
  detailMock.mockReset();
  workshopMock.mockReset();
  sendMock.mockReset();
  publishMock.mockReset();
  workshopMock.mockResolvedValue([]);
}

async function mountView() {
  const wrapper = mount(IssueWorkshopView, { global: { stubs } });
  await flushPromises();
  return wrapper;
}

describe('IssueWorkshopView 工坊对话（FR-ISSUE-09）', () => {
  beforeEach(resetMocks);

  it('空会话呈现模板引导；侧栏在右侧（DOM 序=主区在前）', async () => {
    listMock.mockResolvedValue([]);
    const wrapper = await mountView();
    expect(wrapper.find('[data-testid="workshop-guide"]').text()).toContain('模板');
    const aside = wrapper.find('aside');
    expect(aside.exists()).toBe(true);
    // 布局口径：主对话列先于侧栏（flex 顺序 + 侧栏排右）
    expect(wrapper.find('.workshop-view > .main').exists()).toBe(true);
  });

  it('发送→AI 追问进消息流', async () => {
    listMock.mockResolvedValue([]);
    sendMock.mockResolvedValueOnce({ reply: '先确认字段与规则', issueId: null, issueTitle: null });
    const wrapper = await mountView();
    await wrapper.find('[data-testid="workshop-input"]').setValue('我要做点检');
    await wrapper.find('[data-testid="workshop-send"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="workshop-log"]').text()).toContain('先确认字段');
  });

  it('信息足够→工具创建后出现确认卡并可推送', async () => {
    sendMock.mockResolvedValueOnce({
      reply: '已创建需求《设备点检管理》。规格草稿与三段简报已生成，请在下方卡片确认推送。',
      issueId: 'i9',
      issueTitle: '设备点检管理',
    });
    listMock.mockResolvedValue([issue({ id: 'i9' })]);
    const wrapper = await mountView();
    await wrapper.find('[data-testid="workshop-input"]').setValue('字段有设备与结果');
    await wrapper.find('[data-testid="workshop-send"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="workshop-log"]').text()).toContain('已创建需求');
    const card = wrapper.find('[data-testid="workshop-created-card"]');
    expect(card.exists()).toBe(true);
    publishMock.mockResolvedValue(issue({ id: 'i9', publishedAt: '2026-09-12T03:00:00Z' }));
    await card.find('[data-testid="workshop-publish"]').trigger('click');
    await flushPromises();
    expect(publishMock).toHaveBeenCalledWith('i9');
    expect(wrapper.text()).toContain('需求已确认推送');
  });
});

describe('IssueWorkshopView 工坊失败路径（FR-ISSUE-09）', () => {
  beforeEach(resetMocks);

  it('发送失败内联报错且原稿保留', async () => {
    listMock.mockResolvedValue([]);
    sendMock.mockRejectedValue(new Error('network down'));
    const wrapper = await mountView();
    const input = wrapper.find('[data-testid="workshop-input"]');
    await input.setValue('需求');
    await wrapper.find('[data-testid="workshop-send"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="workshop-error"]').exists()).toBe(true);
    expect((input.element as HTMLTextAreaElement).value).toBe('需求');
  });
});

describe('IssueWorkshopView 需求详情与推送（P23 语义移植）', () => {
  beforeEach(resetMocks);

  it('侧栏选中→详情视图；成稿确认推送→已发布徽标', async () => {
    const mine = issue();
    listMock.mockResolvedValue([mine]);
    detailMock.mockResolvedValue(mine);
    const wrapper = await mountView();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="confirm-card"]').exists()).toBe(false);
    // 无简报（spec null）→ 无确认卡；已发布需求显示状态行
    const published = issue({ publishedAt: '2026-09-12T03:00:00Z' });
    listMock.mockResolvedValue([published]);
    detailMock.mockResolvedValue(published);
    const again = await mountView();
    await again.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    expect(again.find('[data-testid="published-badge"]').exists()).toBe(true);
    expect(again.find('[data-testid="confirm-card"]').exists()).toBe(false);
  });

  it('侧栏可折叠，折叠后仅留展开钮', async () => {
    listMock.mockResolvedValue([issue()]);
    const wrapper = await mountView();
    await wrapper.find('[data-testid="collapse-sidebar"]').trigger('click');
    expect(wrapper.find('[data-testid="expand-sidebar"]').exists()).toBe(true);
  });

  it('新建需求按钮回到工坊对话', async () => {
    listMock.mockResolvedValue([issue()]);
    detailMock.mockResolvedValue(issue());
    const wrapper = await mountView();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    await wrapper.find('[data-testid="new-requirement"]').trigger('click');
    expect(wrapper.find('[data-testid="workshop-chat"]').exists()).toBe(true);
  });
});

describe('IssueWorkshopView 详情分区锚点（P32 分区重排）', () => {
  beforeEach(resetMocks);

  it('详情三分区（头部/澄清/讨论）存在', async () => {
    // 审查 P3-1：为分区 testid 补存在性断言，防后续重构静默丢分区
    listMock.mockResolvedValue([issue()]);
    detailMock.mockResolvedValue(issue());
    const wrapper = await mountView();
    await wrapper.find('[data-testid="issue-entry-i1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="workshop-issue-detail"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="issue-detail-clarify"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="issue-detail-discussion"]').exists()).toBe(true);
  });
});
