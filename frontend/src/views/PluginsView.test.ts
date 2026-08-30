// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { PluginInventoryEntry } from '@/api/plugins';
import PluginsView from '@/views/PluginsView.vue';

vi.mock('@/api/plugins', () => ({
  fetchPluginInventory: vi.fn(),
  importPackage: vi.fn(),
  validatePackage: vi.fn(),
  activateVersion: vi.fn(),
  stopActivation: vi.fn(),
  uninstallPlugin: vi.fn(),
}));

import {
  activateVersion,
  fetchPluginInventory,
  importPackage,
  stopActivation,
  uninstallPlugin,
} from '@/api/plugins';

const fetchMock = vi.mocked(fetchPluginInventory);
const importMock = vi.mocked(importPackage);
const activateMock = vi.mocked(activateVersion);
const stopMock = vi.mocked(stopActivation);
const uninstallMock = vi.mocked(uninstallPlugin);

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

function resetMocks(): void {
  fetchMock.mockReset();
  importMock.mockReset();
  activateMock.mockReset();
  stopMock.mockReset();
  uninstallMock.mockReset();
}

describe('PluginsView 插件清单', () => {
  beforeEach(resetMocks);

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

describe('PluginsView 导入（P15）', () => {
  beforeEach(() => {
    resetMocks();
    window.confirm = () => true;
  });

  it('未选文件时导入/校验禁用', async () => {
    fetchMock.mockResolvedValue(inventory());
    const wrapper = mount(PluginsView);
    await flushPromises();
    const importButton = wrapper.find('[data-testid="import-button"]').element as HTMLButtonElement;
    expect(importButton.disabled).toBe(true);
  });

  it('选择文件后导入成功并刷新清单', async () => {
    fetchMock.mockResolvedValue(inventory());
    const wrapper = mount(PluginsView);
    await flushPromises();

    const input = wrapper.find('[data-testid="plugin-file"]');
    const file = new File(['zip-bytes'], 'plugin.zip', { type: 'application/zip' });
    Object.defineProperty(input.element, 'files', { value: [file] });
    await input.trigger('change');
    importMock.mockResolvedValue({
      pluginId: 'p1',
      version: '1.0.0',
      versionId: 'v9',
      contentHash: 'hash',
      isNew: true,
      dependencies: [],
    });
    await wrapper.find('[data-testid="import-button"]').trigger('click');
    await flushPromises();
    expect(importMock).toHaveBeenCalledTimes(1);
    expect((importMock.mock.calls[0][0] as File).name).toBe('plugin.zip');
    expect(wrapper.text()).toContain('已导入 p1@1.0.0');
  });
});

describe('PluginsView 卡片操作（P15）', () => {
  beforeEach(() => {
    resetMocks();
    window.confirm = () => true;
  });

  it('卡片事件接线：激活/停用/卸载调用对应 API 并刷新', async () => {
    fetchMock.mockResolvedValue(inventory());
    const wrapper = mount(PluginsView);
    await flushPromises();
    activateMock.mockResolvedValue(inventory()[0].activations[0]);
    stopMock.mockResolvedValue(inventory()[0].activations[0]);
    uninstallMock.mockResolvedValue(undefined);

    const activateButtons = wrapper.findAll('button').filter((b) => b.text() === '激活');
    await activateButtons[0].trigger('click');
    await flushPromises();
    expect(activateMock).toHaveBeenCalledWith('v1');

    await wrapper.findAll('button').filter((b) => b.text() === '停用')[0].trigger('click');
    await flushPromises();
    expect(stopMock).toHaveBeenCalledWith('a2');

    await wrapper.findAll('button').filter((b) => b.text() === '卸载')[0].trigger('click');
    await flushPromises();
    expect(uninstallMock).toHaveBeenCalledWith('gen.iabc123');
  });
});

describe('PluginsView 操作防护（P15）', () => {
  beforeEach(() => {
    resetMocks();
    window.confirm = () => true;
  });

  it('卸载须确认：confirm 取消则不调用 API', async () => {
    window.confirm = () => false;
    fetchMock.mockResolvedValue(inventory());
    const wrapper = mount(PluginsView);
    await flushPromises();
    await wrapper.findAll('button').filter((b) => b.text() === '卸载')[0].trigger('click');
    await flushPromises();
    expect(uninstallMock).not.toHaveBeenCalled();
  });

  it('操作失败显示页面级错误（stage+errorCode 诊断保留在卡片）', async () => {
    fetchMock.mockResolvedValue(inventory());
    const wrapper = mount(PluginsView);
    await flushPromises();
    activateMock.mockRejectedValue(
      new ApiError('dependency_missing', '依赖缺失', 400, 'req-2'),
    );
    const activateButtons = wrapper.findAll('button').filter((b) => b.text() === '激活');
    await activateButtons[0].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('依赖缺失');
    expect(wrapper.text()).toContain('req-2');
  });
});
