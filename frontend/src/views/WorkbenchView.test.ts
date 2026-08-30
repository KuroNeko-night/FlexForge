// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const pushMock = vi.fn();
// 路由 mock 需可变：热切换 watch 依赖 route.path 变化（P13）
const routeMock = reactive({ path: '/' });

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
  useRoute: () => routeMock,
}));
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
  syncRemovedActivations: vi.fn(),
}));
vi.mock('@/registry/localeRegistry', async () => {
  const actual = await vi.importActual<typeof import('@/registry/localeRegistry')>(
    '@/registry/localeRegistry',
  );
  return {
    ...actual,
    registerLocalePack: vi.fn(),
    syncRemovedLocalePacks: vi.fn(),
  };
});

import { fetchMenus } from '@/api/meta';
import { fetchActiveThemeAssets, type ActiveThemeAsset } from '@/api/theme';
import { session } from '@/auth/token';
import { registerLocalePack, syncRemovedLocalePacks } from '@/registry/localeRegistry';
import {
  applyTokenOverrides,
  registerThemeAsset,
  syncRemovedActivations,
} from '@/registry/themeRegistry';
import WorkbenchView from '@/views/WorkbenchView.vue';

const menusMock = vi.mocked(fetchMenus);
const themeMock = vi.mocked(fetchActiveThemeAssets);
const registerMock = vi.mocked(registerThemeAsset);
const tokensMock = vi.mocked(applyTokenOverrides);
const syncMock = vi.mocked(syncRemovedActivations);
const localeMock = vi.mocked(registerLocalePack);
const localeSyncMock = vi.mocked(syncRemovedLocalePacks);

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
function resetMocks() {
  menusMock.mockReset();
  themeMock.mockReset();
  registerMock.mockReset();
  tokensMock.mockReset();
  syncMock.mockReset();
  localeMock.mockReset();
  localeSyncMock.mockReset();
  routeMock.path = '/';
  menusMock.mockResolvedValue([{ key: 'workbench', title: '工作台', route: '/workbench' }]);
}

describe('WorkbenchView 主题桥接（P12.5）', () => {
  beforeEach(resetMocks);

  it('资产注册签名 serveUrl，tokens 取回 JSON 应用', async () => {
    themeMock.mockResolvedValue([
      themeAsset('k1', 'background', 'bg.svg'),
      themeAsset('k2', 'tokens', 'theme.json'),
    ]);
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ '--ff-primary': '#123456' }),
    });
    const wrapper = mount(WorkbenchView, { global: { stubs: { RouterView: true } } });
    await flushPromises();
    wrapper.unmount();
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
    wrapper.unmount();
  });

  it('locale 资产取回 {lang,messages} 注册语言包并差量同步（P15）', async () => {
    themeMock.mockResolvedValue([themeAsset('k3', 'locale', 'en.json')]);
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ lang: 'en', messages: { 'menu.workbench': 'Workbench' } }),
    });
    const wrapper = mount(WorkbenchView, { global: { stubs: { RouterView: true } } });
    await flushPromises();
    wrapper.unmount();
    expect(localeMock).toHaveBeenCalledWith('a1', {
      lang: 'en',
      messages: { 'menu.workbench': 'Workbench' },
    });
    expect(localeSyncMock).toHaveBeenCalledWith(['a1']);
    // locale 是 JSON 文档通道，不得走资产注册
    expect(registerMock).not.toHaveBeenCalled();
  });
});

describe('WorkbenchView 热切换（P13）', () => {
  beforeEach(resetMocks);

  it('路由变化重拉聚合并差量撤销（watch 桥接）', async () => {
    session.token = 'tk-hot';
    themeMock.mockResolvedValue([themeAsset('k1', 'background', 'bg.svg')]);
    try {
      const wrapper = mount(WorkbenchView, { global: { stubs: { RouterView: true } } });
      await flushPromises();
      expect(themeMock).toHaveBeenCalledTimes(1);
      expect(syncMock).toHaveBeenCalledWith(['a1']);
      routeMock.path = '/data/some_entity';
      await flushPromises();
      expect(themeMock).toHaveBeenCalledTimes(2);
      expect(syncMock).toHaveBeenCalledTimes(2);
      wrapper.unmount();
    } finally {
      session.token = null;
    }
  });
});
