// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import EntityCards from '@/components/EntityCards.vue';

vi.mock('@/api/meta', () => ({ listEnabledEntities: vi.fn() }));

import { listEnabledEntities } from '@/api/meta';
import type { EntitySummary } from '@/api/types';

const listMock = vi.mocked(listEnabledEntities);

const entity = (name: string, status: EntitySummary['status']): EntitySummary => ({
  id: `e-${name}`,
  name,
  displayName: name,
  status,
  pluginId: null,
  updatedAt: '',
});

const stubs = { RouterLink: { template: '<a><slot /></a>' } };

describe('EntityCards（P12.5 缺陷③：只渲染 enabled 实体）', () => {
  beforeEach(() => {
    listMock.mockReset();
  });

  it('特权角色全量列表中的 disabled/draft 不渲染成入口', async () => {
    listMock.mockResolvedValue([
      entity('demo_material', 'enabled'),
      entity('inventory_item', 'disabled'),
      entity('draft_item', 'draft'),
    ]);
    const wrapper = mount(EntityCards, { global: { stubs } });
    await flushPromises();
    const cards = wrapper.findAll('a');
    expect(cards).toHaveLength(1);
    expect(cards[0].text()).toContain('demo_material');
  });

  it('空列表显示空态提示', async () => {
    listMock.mockResolvedValue([]);
    const wrapper = mount(EntityCards);
    await flushPromises();
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(true);
  });

  it('请求失败显示错误态（含 requestId）', async () => {
    listMock.mockRejectedValue(new ApiError('internal_error', '请求失败', 500, 'req-9'));
    const wrapper = mount(EntityCards);
    await flushPromises();
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('req-9');
  });
});
