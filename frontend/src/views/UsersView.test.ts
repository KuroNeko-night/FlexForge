// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { SystemUser } from '@/api/system';
import UsersView from '@/views/UsersView.vue';

vi.mock('@/api/system', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  assignRoles: vi.fn(),
}));

import { assignRoles, createUser, listUsers } from '@/api/system';

const listMock = vi.mocked(listUsers);
const createMock = vi.mocked(createUser);
const rolesMock = vi.mocked(assignRoles);

const user = (id: number, username: string, roles: string[]): SystemUser => ({
  id,
  username,
  displayName: `显示-${username}`,
  status: 'ACTIVE',
  roles,
});

const page = (items: SystemUser[]) => ({ items, total: items.length, pageNumber: 1, pageSize: 50 });
const baseRows = () => [user(1, 'admin', ['ADMIN']), user(2, 'demo-user', ['USER'])];
// Teleport stub：抽屉渲染在组件树内，供 wrapper.find 定位
const stubs = { teleport: true };

describe('UsersView 列表与权限（P12.5 缺陷③）', () => {
  beforeEach(() => {
    listMock.mockReset();
    createMock.mockReset();
    rolesMock.mockReset();
  });

  it('渲染用户列表（角色/状态）', async () => {
    listMock.mockResolvedValue(page(baseRows()));
    const wrapper = mount(UsersView, { global: { stubs } });
    await flushPromises();
    expect(wrapper.find('[data-testid="users-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('admin');
    expect(wrapper.text()).toContain('demo-user');
  });

  it('403 显示无权限态（服务端授权是边界）', async () => {
    listMock.mockRejectedValue(new ApiError('forbidden', '无权限', 403, null));
    const wrapper = mount(UsersView);
    await flushPromises();
    expect(wrapper.find('[data-state="denied"]').exists()).toBe(true);
  });
});

describe('UsersView 创建与角色调整（P12.5 缺陷③）', () => {
  beforeEach(() => {
    listMock.mockReset();
    createMock.mockReset();
    rolesMock.mockReset();
  });

  it('抽屉创建用户：提交载荷正确并刷新列表', async () => {
    // 测试口令按用户名派生（AuthTestSupport 同口径：源码不落口令字面量）
    const username = 'newbie';
    const derivedPassword = ['pw', username, 'e2e'].join('-');
    listMock.mockResolvedValueOnce(page(baseRows()));
    listMock.mockResolvedValueOnce(page([...baseRows(), user(3, username, ['USER'])]));
    createMock.mockResolvedValue(user(3, username, ['USER']));
    const wrapper = mount(UsersView, { global: { stubs } });
    await flushPromises();
    await wrapper.find('header .ff-btn--primary').trigger('click');
    await wrapper.find('input[name="username"]').setValue(username);
    await wrapper.find('input[name="displayName"]').setValue('新人');
    await wrapper.find('input[name="password"]').setValue(derivedPassword);
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(createMock).toHaveBeenCalledWith({
      username,
      password: derivedPassword,
      displayName: '新人',
      roles: ['USER'],
    });
    expect(wrapper.text()).toContain(username);
  });

  it('角色调整：抽屉打开后展示目标用户', async () => {
    listMock.mockResolvedValue(page(baseRows()));
    rolesMock.mockResolvedValue(user(2, 'demo-user', ['USER', 'DEVELOPER']));
    const wrapper = mount(UsersView, { global: { stubs } });
    await flushPromises();
    await wrapper.findAll('tbody .ff-btn--sm')[1].trigger('click');
    const dialogs = wrapper.findAll('[role="dialog"]');
    expect(dialogs.at(-1)?.text()).toContain('demo-user');
  });
});
