import { defineComponent } from 'vue';
import { describe, expect, it } from 'vitest';

import {
  registerLayout,
  registerWidget,
  resolveLayout,
  revokeLayout,
  revokeLayoutsByActivation,
} from '@/registry/layoutRegistry';

const FIRST = defineComponent({ template: '<div>first</div>' });
const SECOND = defineComponent({ template: '<div>second</div>' });

describe('layout registry（extension.layout 消费面，FR-PLUGIN-10）', () => {
  it('无贡献时返回缺省槽位（兜底布局）', () => {
    const resolved = resolveLayout('workbench.main', [
      { name: 'main', widgetKeys: ['workbench.entities'] },
    ]);
    expect(resolved.value).toEqual([{ name: 'main', widgetKeys: ['workbench.entities'] }]);
  });

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
    const resolved = resolveLayout('page.demo', []);
    expect(resolved.value).toEqual([{ name: 'main', widgetKeys: ['w.first', 'w.second'] }]);

    // 撤销后即时恢复缺省（响应式重算）
    revokeLayout('layout.demo');
    expect(resolveLayout('page.demo', [{ name: 'main', widgetKeys: ['w.first'] }]).value).toEqual([
      { name: 'main', widgetKeys: ['w.first'] },
    ]);
  });

  it('多贡献合并同槽位；按 activationId 批量撤销不残留', () => {
    registerWidget('w.first', FIRST);
    registerWidget('w.second', SECOND);
    registerLayout(
      { key: 'l.a', target: 'page.multi', slots: [{ name: 'main', items: [{ key: 'w.first', order: 1 }] }] },
      'act-7',
    );
    registerLayout(
      { key: 'l.b', target: 'page.multi', slots: [{ name: 'side', items: [{ key: 'w.second', order: 1 }] }] },
      'act-7',
    );
    const merged = resolveLayout('page.multi', []);
    expect(merged.value.map((slot) => slot.name).sort()).toEqual(['main', 'side']);

    expect(revokeLayoutsByActivation('act-7')).toBe(2);
    expect(resolveLayout('page.multi', []).value).toEqual([]);
  });
});
