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

  it('注册即时生效为 CSS 变量（background 以 url() 包裹），撤销即时恢复默认', () => {
    // 持有同一 computed 跨注册/撤销：验证响应式重算（而非新建查询）
    const style = themeStyle();
    expect(style.value).toEqual({});
    const registration = registerThemeAsset({
      key: 'theme.bg',
      kind: 'background',
      path: 'assets/bg.png',
    });
    expect(style.value).toEqual({ '--ff-theme-background': 'url("assets/bg.png")' });

    registration.close();
    expect(style.value).toEqual({});
  });

  it('path 最小防御：拒绝外链/协议相对/空值（P07 S6 纵深防线）', () => {
    expect(() =>
      registerThemeAsset({ key: 't.evil', kind: 'background', path: 'https://evil.example/a.png' }),
    ).toThrow(/相对路径/);
    expect(() =>
      registerThemeAsset({ key: 't.evil2', kind: 'background', path: '//cdn.example/a.png' }),
    ).toThrow(/相对路径/);
    expect(() => registerThemeAsset({ key: 't.evil3', kind: 'icon', path: '' })).toThrow(
      /相对路径/,
    );
  });

  it('同 kind 后注册者胜；特定 scope 覆盖全局；按 activationId 批量撤销', () => {
    registerThemeAsset({ key: 't.global', kind: 'icon', path: 'a.png' }, 'act-t');
    registerThemeAsset({ key: 't.override', kind: 'icon', path: 'b.png' }, 'act-t');
    expect(resolveThemeAsset('icon').value?.path).toBe('b.png');

    registerThemeAsset({ key: 't.scoped', kind: 'icon', path: 'c.png', scope: 'page.demo' });
    expect(resolveThemeAsset('icon', 'page.demo').value?.path).toBe('c.png');
    expect(resolveThemeAsset('icon').value?.path).toBe('b.png');

    expect(revokeThemeAssetsByActivation('act-t')).toBe(2);
    // 全局资产已清（全局视角恢复默认），作用域资产仍服务其作用域
    expect(resolveThemeAsset('icon').value).toBeNull();
    expect(resolveThemeAsset('icon', 'page.demo').value?.path).toBe('c.png');
    revokeThemeAsset('t.scoped');
    expect(resolveThemeAsset('icon', 'page.demo').value).toBeNull();
  });
});
