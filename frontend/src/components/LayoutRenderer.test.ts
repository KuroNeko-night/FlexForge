// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent } from 'vue';
import { describe, expect, it } from 'vitest';

import LayoutRenderer from '@/components/LayoutRenderer.vue';
import { registerLayout, registerWidget, revokeLayout } from '@/registry/layoutRegistry';

const FIRST = defineComponent({ template: '<p data-widget-kind="first">部件一</p>' });
const SECOND = defineComponent({ template: '<p data-widget-kind="second">部件二</p>' });

const DEFAULT_SLOTS = [{ name: 'main', widgetKeys: ['w.first'] }];

function mountRenderer() {
  return mount(LayoutRenderer, { props: { target: 'page.demo', defaultSlots: DEFAULT_SLOTS } });
}

describe('LayoutRenderer（FR-PLUGIN-10 验收 4：按声明渲染 + 撤销恢复缺省）', () => {
  it('无贡献渲染缺省槽位部件', () => {
    registerWidget('w.first', FIRST);
    const wrapper = mountRenderer();
    expect(wrapper.find('[data-widget-kind="first"]').exists()).toBe(true);
  });

  it('布局贡献按声明渲染槽位与部件编排，撤销后即时恢复缺省布局', async () => {
    registerWidget('w.first', FIRST);
    registerWidget('w.second', SECOND);
    const registration = registerLayout({
      key: 'layout.demo',
      target: 'page.demo',
      slots: [
        { name: 'main', items: [{ key: 'w.second', order: 1 }] },
        { name: 'side', items: [{ key: 'w.first', order: 1 }] },
      ],
    });
    let wrapper = mountRenderer();
    await flushPromises();
    expect(wrapper.find('[data-slot="main"] [data-widget-kind="second"]').exists()).toBe(true);
    expect(wrapper.find('[data-slot="side"] [data-widget-kind="first"]').exists()).toBe(true);

    registration.close();
    wrapper = mountRenderer();
    await flushPromises();
    expect(wrapper.find('[data-slot="main"] [data-widget-kind="first"]').exists()).toBe(true);
    expect(wrapper.find('[data-slot="side"]').exists()).toBe(false);
    revokeLayout('layout.demo');
  });
});
