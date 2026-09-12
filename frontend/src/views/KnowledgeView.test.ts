// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { KbEntry } from '@/api/kb';
import { saveSession } from '@/auth/token';
import KnowledgeView from '@/views/KnowledgeView.vue';

vi.mock('@/api/kb', () => ({
  listKbEntries: vi.fn(),
  createKbEntry: vi.fn(),
  updateKbEntry: vi.fn(),
  deleteKbEntry: vi.fn(),
}));

import { createKbEntry, deleteKbEntry, listKbEntries, updateKbEntry } from '@/api/kb';

const listMock = vi.mocked(listKbEntries);
const createMock = vi.mocked(createKbEntry);
const updateMock = vi.mocked(updateKbEntry);
const deleteMock = vi.mocked(deleteKbEntry);

const stubs = { teleport: true };

const entry = (id: string, title: string, category: string | null, content: string): KbEntry => ({
  id,
  title,
  category,
  content,
  createdBy: 'admin',
  updatedAt: '2026-09-12T08:00:00Z',
});

async function mountView() {
  const wrapper = mount(KnowledgeView, { global: { stubs } });
  await flushPromises();
  return wrapper;
}

function resetMocks(): void {
  listMock.mockReset();
  createMock.mockReset();
  updateMock.mockReset();
  deleteMock.mockReset();
  saveSession('t', {
    id: 1,
    username: 'admin',
    displayName: '管理员',
    roles: ['ADMIN'],
  });
}

describe('KnowledgeView 列表与搜索（P28，FR-KB-01）', () => {
  beforeEach(resetMocks);

  it('列表呈现标题/分类/预览；空库呈现空态', async () => {
    listMock.mockResolvedValue([
      entry('kb-1', '差旅报销规范', '财务制度', '30 日内提交报销单'),
      entry('kb-2', '平台使用入门', null, '进入工作台后选择业务应用'),
    ]);
    const wrapper = await mountView();
    const items = wrapper.findAll('[data-testid="kb-item"]');
    expect(items).toHaveLength(2);
    expect(items[0].find('[data-testid="kb-category"]').text()).toBe('财务制度');
    expect(items[0].text()).toContain('差旅报销规范');

    listMock.mockResolvedValue([]);
    const empty = await mountView();
    expect(empty.find('[data-testid="kb-empty"]').exists()).toBe(true);
  });

  it('搜索按标题/分类/内容过滤且无匹配有空态', async () => {
    listMock.mockResolvedValue([
      entry('kb-1', '差旅报销规范', '财务制度', '30 日内提交报销单'),
      entry('kb-2', '平台使用入门', null, '进入工作台后选择业务应用'),
    ]);
    const wrapper = await mountView();
    await wrapper.find('[data-testid="kb-search"]').setValue('报销');
    expect(wrapper.findAll('[data-testid="kb-item"]')).toHaveLength(1);
    await wrapper.find('[data-testid="kb-search"]').setValue('不存在关键词');
    expect(wrapper.find('[data-testid="kb-no-match"]').exists()).toBe(true);
  });
});

describe('KnowledgeView 增删改与失败呈现（P28，FR-KB-01/04）', () => {
  beforeEach(resetMocks);

  it('新建条目：抽屉表单提交并刷新列表', async () => {
    listMock.mockResolvedValue([]);
    createMock.mockResolvedValue(entry('kb-9', '新条目', null, '内容'));
    const wrapper = await mountView();
    await wrapper.find('[data-testid="kb-create"]').trigger('click');
    await wrapper.find('[data-testid="kb-entry-title"]').setValue('新条目');
    await wrapper.find('[data-testid="kb-entry-content"]').setValue('内容');
    await wrapper.find('[data-testid="kb-entry-form"]').trigger('submit');
    await flushPromises();
    expect(createMock).toHaveBeenCalledWith({
      title: '新条目',
      category: null,
      content: '内容',
    });
    expect(listMock).toHaveBeenCalledTimes(2);
  });

  it('编辑条目回填并走更新接口', async () => {
    listMock.mockResolvedValue([entry('kb-1', '差旅报销规范', '财务制度', '30 日内')]);
    updateMock.mockResolvedValue(entry('kb-1', '差旅报销规范 v2', null, '60 日内'));
    const wrapper = await mountView();
    await wrapper.find('[data-testid="kb-edit"]').trigger('click');
    const titleInput = wrapper.find('[data-testid="kb-entry-title"]').element;
    expect((titleInput as HTMLInputElement).value).toBe('差旅报销规范');
    await wrapper.find('[data-testid="kb-entry-title"]').setValue('差旅报销规范 v2');
    await wrapper.find('[data-testid="kb-entry-form"]').trigger('submit');
    await flushPromises();
    expect(updateMock).toHaveBeenCalledWith(
      'kb-1',
      expect.objectContaining({ title: '差旅报销规范 v2' }),
    );
  });
});

describe('KnowledgeView 失败呈现与删除（P28，FR-KB-04）', () => {
  beforeEach(resetMocks);

  it('保存失败留在抽屉内呈现服务端消息', async () => {
    listMock.mockResolvedValue([entry('kb-1', '差旅报销规范', null, '内容')]);
    createMock.mockRejectedValue(
      new ApiError('validation_error', '标题超过 120 字符上限', 400, null),
    );
    const wrapper = await mountView();
    await wrapper.find('[data-testid="kb-create"]').trigger('click');
    await wrapper.find('[data-testid="kb-entry-title"]').setValue('超限标题');
    await wrapper.find('[data-testid="kb-entry-content"]').setValue('内容');
    await wrapper.find('[data-testid="kb-entry-form"]').trigger('submit');
    await flushPromises();
    expect(wrapper.text()).toContain('标题超过 120 字符上限');
  });

  it('删除经确认对话并刷新列表', async () => {
    listMock.mockResolvedValue([entry('kb-1', '差旅报销规范', null, '内容')]);
    deleteMock.mockResolvedValue(undefined);
    const wrapper = await mountView();
    listMock.mockClear();
    await wrapper.find('[data-testid="kb-delete"]').trigger('click');
    expect(wrapper.text()).toContain('将删除知识条目');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(deleteMock).toHaveBeenCalledWith('kb-1');
    expect(listMock).toHaveBeenCalledTimes(1);
  });

  it('非管理员直访呈只读视图（无新建/编辑/删除按钮，条目可读）', async () => {
    saveSession('t', { id: 2, username: 'demo', displayName: '演示', roles: ['USER'] });
    listMock.mockResolvedValue([entry('kb-1', '差旅报销规范', '财务制度', '内容')]);
    const wrapper = await mountView();
    expect(wrapper.findAll('[data-testid="kb-item"]')).toHaveLength(1);
    expect(wrapper.find('[data-testid="kb-create"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="kb-edit"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="kb-delete"]').exists()).toBe(false);
  });
});
