// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { PluginInventoryEntry } from '@/api/plugins';
import PluginsView from '@/views/PluginsView.vue';

vi.mock('@/api/plugins', () => ({ fetchPluginInventory: vi.fn() }));

import { fetchPluginInventory } from '@/api/plugins';

const fetchMock = vi.mocked(fetchPluginInventory);

function inventory(): PluginInventoryEntry[] {
  return [
    {
      pluginId: 'gen.iabc123',
      name: '物料库存规格',
      instanceStatus: 'ACTIVE',
      versions: [
        { versionId: 'v1', version: '0.1.2', createdAt: '2026-08-29T02:00:00Z' },
        { versionId: 'v0', version: '0.1.1', createdAt: '2026-08-28T02:00:00Z' },
      ],
      activations: [
        {
          id: 'a2',
          pluginId: 'gen.iabc123',
          pluginVersionId: 'v1',
          operation: 'ACTIVATE',
          status: 'ACTIVE',
          stage: 'REGISTERED',
          errorCode: null,
          requestedBy: 'test-developer',
          startedAt: '2026-08-29T02:00:01Z',
          finishedAt: null,
        },
        {
          id: 'a1',
          pluginId: 'gen.iabc123',
          pluginVersionId: 'v0',
          operation: 'ACTIVATE',
          status: 'FAILED',
          stage: 'DEPENDENCY_CHECK',
          errorCode: 'dependency_missing',
          requestedBy: 'test-admin',
          startedAt: '2026-08-28T02:00:01Z',
          finishedAt: '2026-08-28T02:00:02Z',
        },
      ],
    },
  ];
}

describe('PluginsView 插件清单', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  it('渲染版本、激活尝试与失败诊断（docs/09 P12 插件页）', async () => {
    fetchMock.mockResolvedValue(inventory());
    const wrapper = mount(PluginsView);
    await flushPromises();
    const text = wrapper.text();
    expect(text).toContain('gen.iabc123');
    expect(text).toContain('0.1.2');
    expect(text).toContain('0.1.1');
    expect(text).toContain('失败于 DEPENDENCY_CHECK（dependency_missing）');
    expect(text).toContain('test-developer');
    expect(wrapper.find('[data-testid="plugins-view"]').exists()).toBe(true);
  });

  it('空清单显示空态', async () => {
    fetchMock.mockResolvedValue([]);
    const wrapper = mount(PluginsView);
    await flushPromises();
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(true);
  });

  it('403 显示无权限态（服务端授权是边界）', async () => {
    fetchMock.mockRejectedValue(new ApiError('forbidden', '无权限', 403, null));
    const wrapper = mount(PluginsView);
    await flushPromises();
    expect(wrapper.find('[data-state="denied"]').exists()).toBe(true);
  });

  it('请求失败显示错误态并带 requestId', async () => {
    fetchMock.mockRejectedValue(new ApiError('internal_error', '请求失败', 500, 'req-1'));
    const wrapper = mount(PluginsView);
    await flushPromises();
    expect(wrapper.find('[data-state="error"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('req-1');
  });
});
