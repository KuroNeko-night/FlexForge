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
  upgradeVersion: vi.fn(),
  fetchPresets: vi.fn(),
  savePreset: vi.fn(),
  applyPreset: vi.fn(),
  deletePreset: vi.fn(),
}));

import {
  activateVersion,
  applyPreset,
  fetchPluginInventory,
  fetchPresets,
  importPackage,
  savePreset,
  stopActivation,
  uninstallPlugin,
  upgradeVersion,
  validatePackage,
} from '@/api/plugins';

const fetchMock = vi.mocked(fetchPluginInventory);
const importMock = vi.mocked(importPackage);
const validateMock = vi.mocked(validatePackage);
const activateMock = vi.mocked(activateVersion);
const stopMock = vi.mocked(stopActivation);
const uninstallMock = vi.mocked(uninstallPlugin);
const upgradeMock = vi.mocked(upgradeVersion);
const fetchPresetsMock = vi.mocked(fetchPresets);
const savePresetMock = vi.mocked(savePreset);
const applyPresetMock = vi.mocked(applyPreset);

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
    {
      pluginId: 'gen.older',
      name: '已停用插件',
      instanceStatus: 'imported',
      versions: [{ versionId: 'v9', version: '0.2.0', createdAt: '2026-09-01T02:00:00Z' }],
      activations: [],
    },
  ];
}

const activeActivation = () => inventory()[0].activations[0];

function resetMocks(): void {
  fetchMock.mockReset().mockResolvedValue(inventory());
  importMock.mockReset();
  validateMock.mockReset();
  activateMock.mockReset().mockResolvedValue(activeActivation());
  stopMock.mockReset().mockResolvedValue(activeActivation());
  uninstallMock.mockReset().mockResolvedValue(undefined);
  upgradeMock.mockReset().mockResolvedValue(activeActivation());
  fetchPresetsMock.mockReset().mockResolvedValue([]);
  savePresetMock.mockReset();
  applyPresetMock.mockReset().mockResolvedValue({ activated: [], stopped: [], failed: [] });
}

type View = Awaited<ReturnType<typeof mountView>>;

async function mountView() {
  const wrapper = mount(PluginsView, { global: { stubs: { teleport: true } } });
  await flushPromises();
  return wrapper;
}

async function clickText(wrapper: View, label: string): Promise<void> {
  await wrapper
    .findAll('button')
    .find((b) => b.text() === label)!
    .trigger('click');
  await flushPromises();
}

async function confirmDialog(wrapper: View): Promise<void> {
  await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
  await flushPromises();
}

describe('PluginsView 插件清单（P21 当前版本卡片）', () => {
  beforeEach(resetMocks);

  it('只呈现当前版本与启停开关，历史版本与激活记录默认收起', async () => {
    const text = (await mountView()).text();
    expect(text).toContain('gen.iabc123');
    expect(text).toContain('v0.1.2');
    expect(text).toContain('已启用');
    expect(text).toContain('最近激活失败于 DEPENDENCY_CHECK · dependency_missing');
    expect(text).toContain('另有 1 个版本');
    // 历史明细不在默认视图（用户裁决：不展示安装修改记录）
    expect(text).not.toContain('0.1.1');
    expect(text).not.toContain('test-developer');
  });

  it('空清单显示空态', async () => {
    fetchMock.mockResolvedValue([]);
    const wrapper = await mountView();
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(true);
  });

  it('403 显示无权限态（服务端授权是边界）', async () => {
    fetchMock.mockRejectedValue(new ApiError('forbidden', '无权限', 403, null));
    const wrapper = await mountView();
    expect(wrapper.find('[data-state="denied"]').exists()).toBe(true);
  });
});

describe('PluginsView 开关启停与版本切换（P21）', () => {
  beforeEach(resetMocks);

  it('开关开=激活当前版本，关=停用当前激活', async () => {
    const wrapper = await mountView();
    await wrapper.findAll('[data-testid="plugin-toggle"]')[1].trigger('click');
    await flushPromises();
    expect(activateMock).toHaveBeenCalledWith('v9');
    // 清单在操作后整体重载，重新查询当前开关（防陈旧 DOM 引用）
    await wrapper.findAll('[data-testid="plugin-toggle"]')[0].trigger('click');
    await flushPromises();
    expect(stopMock).toHaveBeenCalledWith('a2');
  });

  it('展开版本区切换旧版本（启用中经 upgrade 语义）', async () => {
    const wrapper = await mountView();
    await clickText(wrapper, '另有 1 个版本');
    await clickText(wrapper, '切换到此版本');
    expect(upgradeMock).toHaveBeenCalledWith('v0');
  });

  it('开关操作失败显示页面级错误', async () => {
    activateMock.mockRejectedValue(new ApiError('dependency_missing', '依赖缺失', 400, 'req-2'));
    const wrapper = await mountView();
    await wrapper.findAll('[data-testid="plugin-toggle"]')[1].trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('依赖缺失');
    expect(wrapper.text()).toContain('req-2');
  });
});

describe('PluginsView 确认防护（P16 统一确认）', () => {
  beforeEach(resetMocks);

  it('卸载须确认：取消则不调用 API', async () => {
    const wrapper = await mountView();
    await clickText(wrapper, '卸载');
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(true);
    await wrapper
      .findAll('button')
      .filter((b) => b.text() === '取消')
      .at(-1)!
      .trigger('click');
    await flushPromises();
    expect(uninstallMock).not.toHaveBeenCalled();
  });
});

describe('PluginsView 预设接线（FR-PLUGIN-12）', () => {
  beforeEach(resetMocks);

  const preset = {
    id: 'preset-1',
    name: '生产演示',
    entries: [{ pluginId: 'gen.iabc123', versionId: 'v1', version: '0.1.2' }],
    createdBy: 'test-admin',
    createdAt: '2026-09-07T02:00:00Z',
  };

  it('保存当前为预设：输入名称后调用保存并刷新清单', async () => {
    savePresetMock.mockResolvedValue(preset);
    fetchPresetsMock.mockResolvedValue([preset]);
    const wrapper = await mountView();
    await wrapper.find('[data-testid="preset-name-input"]').setValue('生产演示');
    await clickText(wrapper, '保存当前为预设');
    expect(savePresetMock).toHaveBeenCalledWith('生产演示');
    expect(wrapper.text()).toContain('已保存预设 生产演示');
    expect(wrapper.text()).toContain('1 个插件');
  });

  it('应用预设须确认，确认后逐项呈现结果与失败明细', async () => {
    fetchPresetsMock.mockResolvedValue([preset]);
    applyPresetMock.mockResolvedValue({
      activated: ['gen.iabc123'],
      stopped: ['gen.older'],
      failed: [{ pluginId: 'preset.ghost', action: 'activate', message: '插件版本不存在: ghost' }],
    });
    const wrapper = await mountView();
    await clickText(wrapper, '应用');
    expect(applyPresetMock).not.toHaveBeenCalled();
    await confirmDialog(wrapper);
    expect(applyPresetMock).toHaveBeenCalledWith('preset-1');
    expect(wrapper.text()).toContain('已应用预设 生产演示：启用 gen.iabc123；停用 gen.older');
    expect(wrapper.text()).toContain('preset.ghost 启用失败：插件版本不存在: ghost');
  });
});

/** 选包公共流：注入 File 并触发 change（P16 上传区自动校验链）。 */
async function pickFile(wrapper: View, name: string, bytes: string) {
  const input = wrapper.find('[data-testid="plugin-file"]');
  const file = new File([bytes], name, { type: 'application/zip' });
  Object.defineProperty(input.element, 'files', { value: [file] });
  await input.trigger('change');
  await flushPromises();
  return file;
}

describe('PluginsView 上传区（P16）', () => {
  beforeEach(resetMocks);

  it('未选文件时不渲染导入按钮', async () => {
    const wrapper = await mountView();
    expect(wrapper.text()).toContain('拖拽插件包到此处');
    expect(wrapper.find('[data-testid="import-button"]').exists()).toBe(false);
  });

  it('自动校验未通过时给出发现项且导入按钮不可用', async () => {
    const wrapper = await mountView();
    validateMock.mockResolvedValue({ valid: false, preview: null, findings: ['清单缺失'] });
    await pickFile(wrapper, 'bad.zip', 'bad');
    expect(wrapper.text()).toContain('校验未通过');
    expect(wrapper.text()).toContain('清单缺失');
    const button = wrapper.find('[data-testid="import-button"]').element as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('选择文件后自动校验，通过后导入成功并刷新清单', async () => {
    const wrapper = await mountView();
    validateMock.mockResolvedValue({ valid: true, preview: null, findings: [] });
    await pickFile(wrapper, 'plugin.zip', 'zip-bytes');
    expect(wrapper.text()).toContain('校验通过，可导入');
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
    expect((importMock.mock.calls[0][0] as File).name).toBe('plugin.zip');
    expect(wrapper.text()).toContain('已导入 p1 1.0.0');
  });
});
