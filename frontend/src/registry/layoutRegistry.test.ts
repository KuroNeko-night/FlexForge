import type { Component } from 'vue';
import { describe, expect, it } from 'vitest';

import {
  registerLayout,
  registerWidget,
  resolveLayout,
  revokeLayout,
  revokeLayoutsByActivation,
} from '@/registry/layoutRegistry';

// 普通组件对象（测试夹具；不用 defineComponent 以避开 vue/one-component-per-file 对 .ts 的误报）
const FIRST: Component = { template: '<div>first</div>' };
const SECOND: Component = { template: '<div>second</div>' };

function singleSlotLayout(
  key: string,
  target: string,
  slotName: string,
  widgetKey: string,
): import('@/registry/layoutRegistry').LayoutContribution {
  return { key, target, slots: [{ name: slotName, items: [{ key: widgetKey, order: 1 }] }] };
}

describe('layout registry（extension.layout 消费面，FR-PLUGIN-10）', () => {
  it('无贡献时返回缺省槽位（兜底布局）', () => {
    const resolved = resolveLayout('workbench.main', [
      { name: 'main', widgetKeys: ['workbench.entities'] },
    ]);
    expect(resolved.value).toEqual([{ name: 'main', widgetKeys: ['workbench.entities'] }]);
  });

  it('响应式：同一 computed 跨注册/撤销自动重算（即时生效与恢复缺省）', () => {
    registerWidget('w.first', FIRST);
    registerWidget('w.second', SECOND);
    const resolved = resolveLayout('page.reactive', [{ name: 'main', widgetKeys: ['w.first'] }]);
    expect(resolved.value).toEqual([{ name: 'main', widgetKeys: ['w.first'] }]);

    const registration = registerLayout(
      singleSlotLayout('l.re', 'page.reactive', 'main', 'w.second'),
    );
    expect(resolved.value).toEqual([{ name: 'main', widgetKeys: ['w.second'] }]);

    registration.close();
    expect(resolved.value).toEqual([{ name: 'main', widgetKeys: ['w.first'] }]);
  });

  it('多贡献合并同槽位；按 activationId 批量撤销不残留', () => {
    registerWidget('w.first', FIRST);
    registerWidget('w.second', SECOND);
    registerLayout(singleSlotLayout('l.a', 'page.multi', 'main', 'w.first'), 'act-7');
    registerLayout(singleSlotLayout('l.b', 'page.multi', 'side', 'w.second'), 'act-7');
    const merged = resolveLayout('page.multi', []);
    expect(merged.value.map((slot) => slot.name).sort()).toEqual(['main', 'side']);

    expect(revokeLayoutsByActivation('act-7')).toBe(2);
    expect(resolveLayout('page.multi', []).value).toEqual([]);
  });
});

describe('layout registry：贡献合并语义', () => {
  it('布局贡献按声明合并槽位，items 按 order→key 排序，未注册部件跳过', () => {
    registerWidget('w.second', SECOND);
    registerWidget('w.first', FIRST);
    registerLayout({
      key: 'layout.demo',
      target: 'page.demo',
      slots: [
        {
          name: 'main',
          items: [
            { key: 'w.second', order: 2 },
            { key: 'w.first', order: 1 },
            { key: 'w.ghost', order: 0 },
          ],
        },
      ],
    });
    expect(resolveLayout('page.demo', []).value).toEqual([
      { name: 'main', widgetKeys: ['w.first', 'w.second'] },
    ]);
    revokeLayout('layout.demo');
  });
});
