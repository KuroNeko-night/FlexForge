import { describe, expect, it } from 'vitest';

import {
  registerThemeAsset,
  resolveThemeAsset,
  revokeThemeAsset,
  revokeThemeAssetsByActivation,
  themeStyle,
} from '@/registry/themeRegistry';

describe('theme registry（extension.theme-asset 消费面，FR-PLUGIN-11）', () => {
  it('无贡献时无 CSS 变量（平台默认外观兜底）', () => {
    expect(resolveThemeAsset('background').value).toBeNull();
    expect(themeStyle().value).toEqual({});
  });

  it('注册即时生效为 CSS 变量，撤销即时恢复默认', () => {
    const registration = registerThemeAsset({
      key: 'theme.bg',
      kind: 'background',
      path: 'assets/bg.png',
    });
    expect(themeStyle().value).toEqual({ '--ff-theme-background': 'assets/bg.png' });

    registration.close();
    expect(themeStyle().value).toEqual({});
  });

  it('同 kind 后注册者胜；特定 scope 覆盖全局；按 activationId 批量撤销', () => {
    registerThemeAsset({ key: 't.global', kind: 'background', path: 'a.png' }, 'act-t');
    registerThemeAsset({ key: 't.override', kind: 'background', path: 'b.png' }, 'act-t');
    expect(resolveThemeAsset('background').value?.path).toBe('b.png');

    registerThemeAsset({ key: 't.scoped', kind: 'background', path: 'c.png', scope: 'page.demo' });
    expect(resolveThemeAsset('background', 'page.demo').value?.path).toBe('c.png');
    expect(resolveThemeAsset('background').value?.path).toBe('b.png');

    expect(revokeThemeAssetsByActivation('act-t')).toBe(2);
    // 全局资产已清（全局视角恢复默认），作用域资产仍服务其作用域
    expect(resolveThemeAsset('background').value).toBeNull();
    expect(resolveThemeAsset('background', 'page.demo').value?.path).toBe('c.png');
    revokeThemeAsset('t.scoped');
    expect(resolveThemeAsset('background', 'page.demo').value).toBeNull();
  });
});
