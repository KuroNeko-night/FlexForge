// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { IssueRecord } from '@/api/issues';
import { clearSession, saveSession } from '@/auth/token';
import IssueDetail from '@/components/IssueDetail.vue';
import IssuesView from '@/views/IssuesView.vue';

vi.mock('@/api/issues', async () => {
  const actual = await vi.importActual<typeof import('@/api/issues')>('@/api/issues');
  return {
    ...actual,
    listIssues: vi.fn(),
    createIssue: vi.fn(),
    fetchComments: vi.fn(),
    addComment: vi.fn(),
    fetchSpec: vi.fn(),
    clarifyIssue: vi.fn(),
    transitionIssue: vi.fn(),
    saveSpec: vi.fn(),
    fetchPreview: vi.fn(),
    generatePlugin: vi.fn(),
  };
});
vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router');
  return { ...actual, useRouter: () => ({ replace: routerReplace }) };
});

import { createIssue, fetchComments, fetchSpec, listIssues } from '@/api/issues';

const routerReplace = vi.fn();

const listMock = vi.mocked(listIssues);
const createMock = vi.mocked(createIssue);
const commentsMock = vi.mocked(fetchComments);
const specMock = vi.mocked(fetchSpec);

const issue = (overrides: Partial<IssueRecord> = {}): IssueRecord => ({
  id: 'i1',
  title: '物料需求管理',
  description: '需要物料档案与库存记录',
  status: 'SUBMITTED',
  createdBy: 'alice',
  assignedTo: null,
  labels: ['库存'],
  createdAt: '2026-08-30T02:00:00Z',
  updatedAt: '2026-08-30T02:00:00Z',
  publishedAt: null,
  ...overrides,
});

function resetMocks(): void {
  listMock.mockReset();
  createMock.mockReset();
  commentsMock.mockReset();
  specMock.mockReset();
}

const stubs = { teleport: true };

describe('IssuesView 列表渲染（P15）', () => {
  beforeEach(() => {
    resetMocks();
    // 列表/详情用例走开发者信息面（P23 起纯 USER 渲染对话工作台）
    saveSession('t', { id: 1, username: 'alice', displayName: 'A', roles: ['DEVELOPER'] });
  });

  it('渲染列表并按状态徽章展示；选中后展示详情', async () => {
    listMock.mockResolvedValue([issue(), issue({ id: 'i2', title: '第二条' })]);
    const wrapper = mount(IssuesView, { global: { stubs } });
    await flushPromises();
    expect(wrapper.find('[data-testid="issue-list"]').text()).toContain('物料需求管理');
    expect(wrapper.text()).toContain('待审核');

    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
    await wrapper.find('[data-testid="issue-item-i1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="issue-detail"]').exists()).toBe(true);
  });

  it('403 显示无权限态；请求失败显示错误态', async () => {
    listMock.mockRejectedValue(new ApiError('forbidden', '无权限', 403, null));
    const denied = mount(IssuesView, { global: { stubs } });
    await flushPromises();
    expect(denied.find('[data-state="denied"]').exists()).toBe(true);

    listMock.mockRejectedValue(new ApiError('internal_error', '请求失败', 500, 'req-9'));
    const errored = mount(IssuesView, { global: { stubs } });
    await flushPromises();
    expect(errored.find('[data-state="error"]').exists()).toBe(true);
    expect(errored.text()).toContain('req-9');
  });
});

describe('IssuesView 创建与角色（P15）', () => {
  beforeEach(() => {
    resetMocks();
    saveSession('t', { id: 1, username: 'alice', displayName: 'A', roles: ['DEVELOPER'] });
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('创建走抽屉表单：标签拆分、成功后选中新建 Issue', async () => {
    listMock.mockResolvedValue([]);
    const wrapper = mount(IssuesView, { global: { stubs } });
    await flushPromises();
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(true);

    const newButton = wrapper.findAll('button').find((b) => b.text().includes('新建 Issue'));
    expect(newButton).toBeDefined();
    await newButton!.trigger('click');
    await wrapper.find('input[name="title"]').setValue('物料需求管理');
    await wrapper.find('textarea[name="description"]').setValue('需要物料档案');
    await wrapper.find('input[name="labels"]').setValue('库存, 现场');
    createMock.mockResolvedValue(issue());
    listMock.mockResolvedValue([issue()]);
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(createMock).toHaveBeenCalledWith({
      title: '物料需求管理',
      description: '需要物料档案',
      labels: ['库存', '现场'],
    });
    expect(wrapper.find('[data-testid="issue-detail"]').exists()).toBe(true);
  });

  it('USER 角色不渲染开发者面板，作者可见 AI 对话入口', async () => {
    saveSession('t', { id: 3, username: 'carol', displayName: 'C', roles: ['USER'] });
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
    const wrapper = mount(IssueDetail, { props: { issue: issue() } });
    await flushPromises();
    expect(wrapper.find('[data-testid="clarify-chat"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="transition-section"]').exists()).toBe(false);
    clearSession();
  });
});

describe('IssuesView 用户端工作台（P23 FR-ISSUE-07；P29 整合后重定向）', () => {
  beforeEach(() => {
    resetMocks();
    saveSession('t', { id: 2, username: 'bob', displayName: 'B', roles: ['USER'] });
  });

  it('纯 USER 角色重定向 AI 助手整合页（Issue 模式），不渲染开发者列表', async () => {
    listMock.mockResolvedValue([issue()]);
    routerReplace.mockClear();
    const wrapper = mount(IssuesView, { global: { stubs } });
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith('/assistant');
    expect(globalThis.sessionStorage?.getItem('flexforge.assistant.mode')).toBe('issues');
    expect(wrapper.find('[data-testid="issue-list"]').exists()).toBe(false);
    clearSession();
  });

  it('身份未知（硬刷新）不误重定向：回填为开发者后渲染面板', async () => {
    // 子路由挂载先于父壳 fetchMe——session.user=null 模拟未知身份
    const tokenModule = await import('@/auth/token');
    tokenModule.session.user = null;
    listMock.mockResolvedValue([issue()]);
    routerReplace.mockClear();
    const wrapper = mount(IssuesView, { global: { stubs } });
    await flushPromises();
    expect(routerReplace).not.toHaveBeenCalled();
    // 身份回填为开发者 → 面板出现，仍不重定向
    tokenModule.saveSession('t', {
      id: 1,
      username: 'dev',
      displayName: 'D',
      roles: ['DEVELOPER'],
    });
    await flushPromises();
    expect(wrapper.find('[data-testid="issue-list"]').exists()).toBe(true);
    expect(routerReplace).not.toHaveBeenCalled();
    clearSession();
  });
});
