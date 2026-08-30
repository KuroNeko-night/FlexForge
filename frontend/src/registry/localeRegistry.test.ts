import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  availableLanguages,
  currentLanguage,
  registerLocalePack,
  setLanguage,
  syncRemovedLocalePacks,
  t,
} from '@/registry/localeRegistry';

/** P15 locale registry：注册/撤销/语言切换与文案覆盖（FR-SETUP-02）。 */
describe('localeRegistry', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => undefined,
    });
    syncRemovedLocalePacks([]);
    setLanguage('zh-CN');
  });

  it('基线仅 zh-CN；t() 回退调用方给定文案', () => {
    expect(availableLanguages()).toEqual(['zh-CN']);
    expect(t('plugins.title', '插件管理')).toBe('插件管理');
  });

  it('注册语言包后出现可选语言，切换即覆盖命中键、未命中键仍回退', () => {
    registerLocalePack('a1', {
      lang: 'en',
      messages: { 'plugins.title': 'Plugins' },
    });
    expect(availableLanguages()).toEqual(['zh-CN', 'en']);
    setLanguage('en');
    expect(t('plugins.title', '插件管理')).toBe('Plugins');
    expect(t('unknown.key', '未覆盖的文案')).toBe('未覆盖的文案');
  });

  it('差量撤销：语言包移除后语言回退基线', () => {
    registerLocalePack('a1', { lang: 'en', messages: { 'plugins.title': 'Plugins' } });
    setLanguage('en');
    expect(currentLanguage()).toBe('en');
    syncRemovedLocalePacks([]);
    expect(currentLanguage()).toBe('zh-CN');
    expect(availableLanguages()).toEqual(['zh-CN']);
    expect(t('plugins.title', '插件管理')).toBe('插件管理');
  });

  it('setLanguage 拒绝不可用语言（不会切到未注册语言）', () => {
    setLanguage('en');
    expect(currentLanguage()).toBe('zh-CN');
  });
});

describe('localeRegistry 消息键白名单', () => {
  beforeEach(() => {
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: () => undefined,
    });
    syncRemovedLocalePacks([]);
    setLanguage('zh-CN');
  });

  it('驼峰段合法（live 排障回归：en.json 曾被全小写正则拒绝）', () => {
    // pleaseWait/displayName 等 camelCase 键被拒后遭单资产 catch 静默吞掉，
    // 表现为"语言缺失仅基线"——此用例固化根因修复
    registerLocalePack('a3', {
      lang: 'xx',
      messages: { 'login.pleaseWait': 'Wait', 'settings.currentLanguage': 'Lang' },
    });
    expect(availableLanguages()).toContain('xx');
  });

  it('非界面文案 key（CSS/URL 形态）拒绝', () => {
    expect(() =>
      registerLocalePack('a2', { lang: 'fr', messages: { '--ff-primary': 'red' } }),
    ).toThrow('locale 消息键/值非法');
    expect(() =>
      registerLocalePack('a2', { lang: 'fr', messages: { 'javascript:alert': 'x' } }),
    ).toThrow('locale 消息键/值非法');
  });
});
