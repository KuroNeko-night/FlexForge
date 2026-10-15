// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import { batchUpdateStatus, listUsers, type SystemUser } from '@/api/system';
import { clearSession, saveSession } from '@/auth/token';
import UsersView from '@/views/UsersView.vue';

vi.mock('@/api/system', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  assignRoles: vi.fn(),
  updateStatus: vi.fn(),
  batchUpdateStatus: vi.fn(),
}));

const listMock = vi.mocked(listUsers);
const batchMock = vi.mocked(batchUpdateStatus);
const user = (id: number, status: SystemUser['status'] = 'ACTIVE'): SystemUser => ({
  id,
  username: `user-${id}`,
  displayName: `User ${id}`,
  roles: ['USER'],
  status,
});
const page = (pageNumber: number, items: SystemUser[]) => ({
  pageNumber,
  pageSize: 50,
  total: 100,
  items,
});
const mountUsers = () => mount(UsersView, { global: { stubs: { teleport: true } } });

// Issue #51, FR-AUTH-03/05/06.
beforeEach(() => {
  vi.resetAllMocks();
  saveSession('test-session', {
    id: 999,
    username: 'operator',
    displayName: 'Operator',
    roles: ['ADMIN'],
  });
  listMock.mockResolvedValueOnce(page(1, [user(1), user(2)]));
});

afterEach(clearSession);

it('requests the second page, clears selection and supports returning', async () => {
  listMock.mockResolvedValueOnce(page(2, [user(51)]));
  listMock.mockResolvedValueOnce(page(1, [user(1), user(2)]));
  const wrapper = mountUsers();
  await flushPromises();
  expect(listMock).toHaveBeenCalledWith(1, 50);
  await wrapper.get('[data-testid="select-all-users"]').setValue(true);
  await wrapper.get('.pager button:last-child').trigger('click');
  await flushPromises();
  expect(listMock).toHaveBeenLastCalledWith(2, 50);
  expect(wrapper.find('[data-testid="select-user-1"]').exists()).toBe(false);
  expect(wrapper.get('[data-testid="select-user-51"]').element).toHaveProperty('checked', false);
  expect(wrapper.find('[data-testid="batch-block"]').exists()).toBe(false);
  expect(wrapper.get('.pager button:last-child').attributes('disabled')).toBeDefined();
  await wrapper.get('.pager button:first-child').trigger('click');
  await flushPromises();
  expect(listMock).toHaveBeenLastCalledWith(1, 50);
  expect(wrapper.get('[data-testid="select-user-1"]').element).toHaveProperty('checked', false);
  wrapper.unmount();
});

it('retries the failed target page without stale rows or selection', async () => {
  listMock.mockRejectedValueOnce(new ApiError('internal_error', 'page failed', 500, null));
  listMock.mockResolvedValueOnce(page(2, [user(51)]));
  const wrapper = mountUsers();
  await flushPromises();
  await wrapper.get('[data-testid="select-user-2"]').setValue(true);
  await wrapper.get('.pager button:last-child').trigger('click');
  await flushPromises();
  expect(wrapper.get('[data-state="error"]').text()).toContain('page failed');
  expect(wrapper.find('[data-testid="users-table"]').exists()).toBe(false);
  expect(wrapper.find('[data-testid="batch-block"]').exists()).toBe(false);
  await wrapper.get('[data-testid="users-retry"]').trigger('click');
  await flushPromises();
  expect(listMock).toHaveBeenLastCalledWith(2, 50);
  expect(wrapper.find('[data-state="error"]').exists()).toBe(false);
  expect(wrapper.text()).toContain('user-51');
  wrapper.unmount();
});

it('renders permission denial on paging without exposing old data', async () => {
  listMock.mockRejectedValueOnce(new ApiError('permission_denied', 'denied', 403, null));
  const wrapper = mountUsers();
  await flushPromises();
  await wrapper.get('.pager button:last-child').trigger('click');
  await flushPromises();
  expect(wrapper.find('[data-state="denied"]').exists()).toBe(true);
  expect(wrapper.find('[data-testid="users-table"]').exists()).toBe(false);
  expect(wrapper.find('.pager').exists()).toBe(false);
  wrapper.unmount();
});

it('keeps paging reachable when every row on a page is hidden', async () => {
  listMock.mockReset();
  listMock.mockResolvedValueOnce(page(1, [user(1, 'BLOCKED')]));
  listMock.mockResolvedValueOnce(page(2, [user(51, 'BLOCKED')]));
  const wrapper = mountUsers();
  await flushPromises();
  expect(wrapper.findAll('tbody tr')).toHaveLength(0);
  await wrapper.get('.pager button:last-child').trigger('click');
  await flushPromises();
  expect(wrapper.findAll('tbody tr')).toHaveLength(0);
  await wrapper.get('[data-testid="toggle-blocked"]').trigger('click');
  expect(wrapper.text()).toContain('user-51');
  wrapper.unmount();
});

it('does not change pages while a batch operation is running', async () => {
  let finish!: (users: SystemUser[]) => void;
  batchMock.mockReturnValue(new Promise((resolve) => (finish = resolve)));
  listMock.mockResolvedValueOnce(page(1, [user(1), user(2)]));
  const wrapper = mountUsers();
  await flushPromises();
  await wrapper.get('[data-testid="select-user-2"]').setValue(true);
  await wrapper.get('[data-testid="batch-activate"]').trigger('click');
  await wrapper.get('.pager button:last-child').trigger('click');
  expect(listMock).toHaveBeenCalledTimes(1);
  finish([user(2)]);
  await flushPromises();
  expect(listMock).toHaveBeenLastCalledWith(1, 50);
  expect(batchMock).toHaveBeenCalledWith([2], 'ACTIVE');
  wrapper.unmount();
});
