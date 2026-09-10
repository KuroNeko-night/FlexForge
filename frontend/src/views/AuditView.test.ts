// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { AuditEventRecord } from '@/api/system';
import AuditView from '@/views/AuditView.vue';

vi.mock('@/api/system', () => ({
  queryAuditEvents: vi.fn(),
}));

import { queryAuditEvents } from '@/api/system';

const queryMock = vi.mocked(queryAuditEvents);

const events = (): AuditEventRecord[] => [
  {
    id: 'e1',
    actor: 'admin',
    action: 'auth.login',
    objectId: 'u-1',
    result: 'success',
    occurredAt: '2026-09-10T04:00:00Z',
  },
  {
    id: 'e2',
    actor: 'operator',
    action: 'user.status.update',
    objectId: '3',
    result: 'failure',
    occurredAt: '2026-09-10T04:01:00Z',
  },
];

describe('AuditView 审计日志页（P24，FR-AUTH-04）', () => {
  beforeEach(() => {
    queryMock.mockReset();
  });

  it('渲染审计表格：行内容与结果徽标', async () => {
    queryMock.mockResolvedValue({ items: events(), total: 21, pageNumber: 1, pageSize: 20 });
    const wrapper = mount(AuditView);
    await flushPromises();
    expect(wrapper.find('[data-testid="audit-table"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('auth.login');
    expect(wrapper.text()).toContain('user.status.update');
    expect(wrapper.find('.result-chip[data-result="success"]').exists()).toBe(true);
    expect(wrapper.find('.result-chip[data-result="failure"]').exists()).toBe(true);
    expect(queryMock).toHaveBeenCalledWith({ page: 1, pageSize: 20 });
  });

  it('过滤提交：携带非空过滤参数并回到第一页', async () => {
    queryMock.mockResolvedValue({ items: events(), total: 2, pageNumber: 1, pageSize: 20 });
    const wrapper = mount(AuditView);
    await flushPromises();
    await wrapper.find('[data-testid="audit-filter-actor"]').setValue('admin');
    await wrapper.find('[data-testid="audit-filters"]').trigger('submit');
    await flushPromises();
    expect(queryMock).toHaveBeenLastCalledWith({
      page: 1,
      pageSize: 20,
      actor: 'admin',
      action: undefined,
      objectId: undefined,
    });
  });

  it('403 显示无权限态（服务端授权是边界）', async () => {
    queryMock.mockRejectedValue(new ApiError('forbidden', '无权限', 403, null));
    const wrapper = mount(AuditView);
    await flushPromises();
    expect(wrapper.find('[data-state="denied"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="audit-table"]').exists()).toBe(false);
  });
});
