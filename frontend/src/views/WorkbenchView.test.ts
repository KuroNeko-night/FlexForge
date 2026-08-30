// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const pushMock = vi.fn();

vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }));
vi.mock('@/api/meta', () => ({ fetchMenus: vi.fn() }));
vi.mock('@/api/auth', () => ({ fetchMe: vi.fn(), logout: vi.fn() }));
vi.mock('@/api/theme', () => ({ fetchActiveThemeAssets: vi.fn() }));
vi.mock('@/registry/menuRegistry', () => ({
  mergedMenus: (menus: unknown[]) => menus,
}));
vi.mock('@/registry/themeRegistry', () => ({
  themeStyle: () => ({ value: {} }),
  registerThemeAsset: vi.fn(),
  applyTokenOverrides: vi.fn(),
}));

import { fetchMenus } from '@/api/meta';
import { fetchActiveThemeAssets, type ActiveThemeAsset } from '@/api/theme';
import { applyTokenOverrides, registerThemeAsset } from '@/registry/themeRegistry';
import WorkbenchView from '@/views/WorkbenchView.vue';

const menusMock = vi.mocked(fetchMenus);
const themeMock = vi.mocked(fetchActiveThemeAssets);
const registerMock = vi.mocked(registerThemeAsset);
const tokensMock = vi.mocked(applyTokenOverrides);

const themeAsset = (key: string, kind: ActiveThemeAsset['kind'], file: string): ActiveThemeAsset =>
  ({
    activationId: 'a1',
    pluginId: 'theme.default',
    key,
    kind,
    path: `assets/${file}`,
    scope: null,
    serveUrl: `/api/v1/plugins/activations/a1/assets/assets/${file}?exp=1&sig=${key}`,
  }) as ActiveThemeAsset;

/**
 * 壳层主题桥接（PR #32 审查 P1 的测试掩蔽修复）：聚合拉取→资产注册签名 URL、
 * tokens 取回 JSON 应用；聚合失败静默不破壳。
 */
describe('WorkbenchView 主题桥接（P12.5）', () => {
  beforeEach(() => {
    menusMock.mockReset();
    themeMock.mockReset();
    registerMock.mockReset();
    tokensMock.mockReset();
    menusMock.mockResolvedValue([{ key: 'workbench', title: '工作台', route: '/workbench' }]);
  });

  it('资产注册签名 serveUrl，tokens 取回 JSON 应用', async () => {
    themeMock.mockResolvedValue([
      themeAsset('k1', 'background', 'bg.svg'),
      themeAsset('k2', 'tokens', 'theme.json'),
    ]);
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ '--ff-primary': '#123456' }),
    });
    mount(WorkbenchView, { global: { stubs: { RouterView: true } } });
    await flushPromises();
    expect(registerMock).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'background', path: expect.stringContaining('sig=') }),
      'a1',
    );
    expect(tokensMock).toHaveBeenCalledWith('a1', { '--ff-primary': '#123456' });
  });

  it('聚合失败静默降级：壳层正常渲染菜单', async () => {
    themeMock.mockRejectedValue(new Error('down'));
    const wrapper = mount(WorkbenchView, { global: { stubs: { RouterView: true } } });
    await flushPromises();
    expect(wrapper.text()).toContain('工作台');
    expect(registerMock).not.toHaveBeenCalled();
  });
});
