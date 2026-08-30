import { describe, expect, it } from 'vitest';

import {
  applyTokenOverrides,
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
});

describe('theme registry：path 防御与分支', () => {
  // 覆盖复审实测的绕过形态：scheme、协议相对、空白前缀、单斜杠 scheme、空值
  const BAD_PATHS = [
    'https://evil.example/a.png',
    '//cdn.example/a.png',
    '',
    ' http://evil.example/a.png',
    'http:/evil.example/a.png',
    'JavaScript:alert(1)',
  ];

  it('path 最小防御：拒绝外链/协议相对/空白/单斜杠 scheme（P07 S6 纵深防线）', () => {
    for (const path of BAD_PATHS) {
      expect(() => registerThemeAsset({ key: 't.evil', kind: 'icon', path })).toThrow(/相对路径/);
    }
  });

  it('background 转义分支与 icon 透传分支', () => {
    const style = themeStyle();
    registerThemeAsset({ key: 't.quote', kind: 'background', path: 'a"b\\c.png' });
    registerThemeAsset({ key: 't.icon', kind: 'icon', path: 'icons/box.svg' });
    expect(style.value).toEqual({
      '--ff-theme-background': 'url("a\\"b\\\\c.png")',
      '--ff-theme-icon': 'icons/box.svg',
    });
    revokeThemeAsset('t.quote');
    revokeThemeAsset('t.icon');
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

describe('theme registry：P12.5 tokens 通道（--ff-* 键白名单）', () => {
  it('tokens 覆盖注入 themeStyle，撤销按 activationId 恢复基线', () => {
    applyTokenOverrides('act-t1', { '--ff-primary': '#c2571c', '--ff-radius-md': '14px' });
    expect(themeStyle().value['--ff-primary']).toBe('#c2571c');
    expect(themeStyle().value['--ff-radius-md']).toBe('14px');
    // 后应用者覆盖同名键（多插件并存：后注册胜）
    applyTokenOverrides('act-t2', { '--ff-primary': '#0a7a4d' });
    expect(themeStyle().value['--ff-primary']).toBe('#0a7a4d');
    revokeThemeAssetsByActivation('act-t2');
    expect(themeStyle().value['--ff-primary']).toBe('#c2571c');
    revokeThemeAssetsByActivation('act-t1');
    expect(themeStyle().value['--ff-primary']).toBeUndefined();
  });

  it('键白名单：拒绝非 --ff- 前缀键与非字符串值（S5 纵深）', () => {
    expect(() => applyTokenOverrides('act-bad', { color: 'red' })).toThrow(/--ff-/);
    expect(() => applyTokenOverrides('act-bad', { '--ff-primary': 42 })).toThrow(/--ff-/);
    expect(() => applyTokenOverrides('act-bad', { '--FF-INJECT': 'x' })).toThrow(/--ff-/);
  });
});
