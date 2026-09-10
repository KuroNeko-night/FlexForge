// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { SystemUser } from '@/api/system';
import UsersView from '@/views/UsersView.vue';

vi.mock('@/api/system', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  assignRoles: vi.fn(),
  updateStatus: vi.fn(),
  batchUpdateStatus: vi.fn(),
}));

import { assignRoles, batchUpdateStatus, createUser, listUsers, updateStatus } from '@/api/system';
import { clearSession, saveSession } from '@/auth/token';

const listMock = vi.mocked(listUsers);
const createMock = vi.mocked(createUser);
const rolesMock = vi.mocked(assignRoles);
const statusMock = vi.mocked(updateStatus);
const batchMock = vi.mocked(batchUpdateStatus);

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

/** 批量用例共用：清桩 + 以管理员身份操作（selfId=9 不与列表行重叠）。 */
function resetBatchMocks(): void {
  listMock.mockReset();
  createMock.mockReset();
  rolesMock.mockReset();
  statusMock.mockReset();
  batchMock.mockReset();
  saveSession('t', { id: 9, username: 'operator', displayName: '操作者', roles: ['ADMIN'] });
}

/** 挂载并勾选两个可批量账号（返回批量条断言用包装）。 */
async function mountWithTwoSelected() {
  listMock.mockResolvedValue(page(baseRows()));
  const wrapper = mount(UsersView, { global: { stubs } });
  await flushPromises();
  await wrapper.find('[data-testid="select-user-1"]').setValue(true);
  await wrapper.find('[data-testid="select-user-2"]').setValue(true);
  return wrapper;
}

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

describe('UsersView 停启用（P13）', () => {
  beforeEach(() => {
    listMock.mockReset();
    createMock.mockReset();
    rolesMock.mockReset();
    statusMock.mockReset();
  });

  it('开关切换调用 status API 并更新行；失败行级提示不毁页', async () => {
    listMock.mockResolvedValue(page(baseRows()));
    statusMock.mockResolvedValue({ ...baseRows()[1], status: 'BLOCKED' });
    const wrapper = mount(UsersView, { global: { stubs } });
    await flushPromises();
    const switches = wrapper.findAllComponents({ name: 'BaseSwitch' });
    expect(switches.length).toBe(2);
    switches[1].vm.$emit('update:modelValue', false);
    await flushPromises();
    expect(statusMock).toHaveBeenCalledWith(2, 'BLOCKED');
    expect(wrapper.text()).toContain('停用');
    // 失败路径：行级错误提示，表格仍在
    statusMock.mockReset();
    statusMock.mockRejectedValue(
      new ApiError('validation_error', '不能变更自己的账号状态', 400, null),
    );
    wrapper.findAllComponents({ name: 'BaseSwitch' })[1].vm.$emit('update:modelValue', true);
    await flushPromises();
    expect(wrapper.find('[data-testid="users-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('不能变更自己的账号状态');
  });
});

describe('UsersView 批量停用流程（P24，FR-AUTH-05）', () => {
  beforeEach(resetBatchMocks);

  afterEach(() => {
    clearSession();
  });

  it('勾选两人→批量停用→危险确认→单请求提交并清选', async () => {
    batchMock.mockResolvedValue([
      { ...baseRows()[0], status: 'BLOCKED' },
      { ...baseRows()[1], status: 'BLOCKED' },
    ]);
    const wrapper = await mountWithTwoSelected();
    expect(wrapper.find('[data-testid="user-batch-bar"]').text()).toContain('已选 2 项');

    await wrapper.find('[data-testid="batch-block"]').trigger('click');
    const dialog = wrapper.findAll('[role="dialog"]').at(-1);
    expect(dialog?.text()).toContain('停用选中的 2 个账号');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(batchMock).toHaveBeenCalledWith([1, 2], 'BLOCKED');
    // 成功后清选：批量条隐藏，列表重载
    expect(wrapper.find('[data-testid="user-batch-bar"]').exists()).toBe(false);
  });
});

describe('UsersView 批量启用与选择守卫（P24）', () => {
  beforeEach(resetBatchMocks);

  afterEach(() => {
    clearSession();
  });

  it('批量启用免确认直发；失败呈现错误且选择保留（可重试）', async () => {
    listMock.mockResolvedValue(page(baseRows()));
    batchMock.mockRejectedValue(
      new ApiError('validation_error', '批量目标不能包含自己的账号', 400, null),
    );
    const wrapper = mount(UsersView, { global: { stubs } });
    await flushPromises();
    await wrapper.find('[data-testid="select-user-2"]').setValue(true);
    await wrapper.find('[data-testid="batch-activate"]').trigger('click');
    await flushPromises();
    expect(batchMock).toHaveBeenCalledWith([2], 'ACTIVE');
    const bar = wrapper.find('[data-testid="user-batch-bar"]');
    expect(bar.text()).toContain('批量目标不能包含自己的账号');
    expect(bar.text()).toContain('已选 1 项');
  });

  it('自己那行复选禁用（与单人路径同守卫），他行可选', async () => {
    saveSession('t', { id: 1, username: 'admin', displayName: '管理员', roles: ['ADMIN'] });
    listMock.mockResolvedValue(page(baseRows()));
    const wrapper = mount(UsersView, { global: { stubs } });
    await flushPromises();
    expect(
      (wrapper.find('[data-testid="select-user-1"]').element as HTMLInputElement).disabled,
    ).toBe(true);
    expect(
      (wrapper.find('[data-testid="select-user-2"]').element as HTMLInputElement).disabled,
    ).toBe(false);
  });
});
