import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

import { LANGUAGE_LABELS } from './localeRegistry';

/**
 * 语言包资产契约（P26，FR-SETUP-02）：en/ja/fr/es 四包键集一致（缺键回退基线
 * 但不应成片）、不含插件内容键（只翻译系统 chrome——插件名/菜单/实体不翻译）、
 * 值非空字符串、lang 字段正确、语言在 LANGUAGE_LABELS 有展示名。
 */

const PLUGIN_ROOT = resolve(__dirname, '../../../plugins');
const KEY_PATTERN = /^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$/;
const PACKS: Array<{ dir: string; lang: string }> = [
  { dir: 'locale-en', lang: 'en' },
  { dir: 'locale-ja', lang: 'ja' },
  { dir: 'locale-fr', lang: 'fr' },
  { dir: 'locale-es', lang: 'es' },
];
const STATUSES = [
  'SUBMITTED',
  'APPROVED',
  'RETURNED',
  'IN_TESTING',
  'DEV_FAILED',
  'TESTED',
  'FEEDBACK',
  'DONE',
  'CLOSED',
];
/** 模板字面量键族（代码侧无法静态提取，契约锁定）。 */
const REQUIRED_TEMPLATE_KEYS: string[] = [
  'action.record.detail',
  'action.record.edit',
  'action.record.delete',
  'menu.workbench',
  'menu.data-model',
  'menu.file-tools',
  'menu.system-management',
  'menu.system-audit',
  'menu.platform.issues',
  'menu.platform.assistant',
  'menu.platform.knowledge',
  'menu.platform.plugins',
  'menu.platform.settings',
  ...STATUSES.flatMap((s) => [`issues.status.${s}`, `issues.transition.${s}`]),
];

function loadPack(dir: string, lang: string): Record<string, string> {
  const raw = JSON.parse(
    readFileSync(resolve(PLUGIN_ROOT, dir, 'assets', `${lang}.json`), 'utf-8'),
  );
  expect(raw.lang).toBe(lang);
  return raw.messages as Record<string, string>;
}

describe('语言包资产契约（P26 多语言）', () => {
  it('四包键集一致且值合法', () => {
    const messages = PACKS.map((p) => loadPack(p.dir, p.lang));
    const base = Object.keys(messages[0]).sort();
    expect(base.length).toBeGreaterThan(300);
    for (const pack of messages) {
      expect(Object.keys(pack).sort()).toEqual(base);
      for (const [key, value] of Object.entries(pack)) {
        expect(KEY_PATTERN.test(key), `非法键: ${key}`).toBe(true);
        expect(typeof value).toBe('string');
      }
    }
  });

  it('不含插件内容键（menu.example.*/menu.gen.*——只翻译系统）', () => {
    for (const p of PACKS) {
      const keys = Object.keys(loadPack(p.dir, p.lang));
      const forbidden = keys.filter(
        (key) => key.startsWith('menu.example.') || key.startsWith('menu.gen.'),
      );
      expect(forbidden).toEqual([]);
    }
  });

  it('每包语言在设置页标签表有展示名', () => {
    for (const p of PACKS) {
      expect(LANGUAGE_LABELS[p.lang]).toBeTruthy();
    }
  });

  it('模板字面量键族齐全（记录动作/状态机/菜单）', () => {
    for (const p of PACKS) {
      const keys = new Set(Object.keys(loadPack(p.dir, p.lang)));
      for (const key of REQUIRED_TEMPLATE_KEYS) {
        expect(keys.has(key), `${p.lang} 缺 ${key}`).toBe(true);
      }
    }
  });
});
