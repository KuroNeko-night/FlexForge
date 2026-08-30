// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import BaseButton from '@/components/ui/BaseButton.vue';
import BaseDrawer from '@/components/ui/BaseDrawer.vue';
import BaseSwitch from '@/components/ui/BaseSwitch.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';

describe('BaseButton / BaseSwitch（docs/09 P12.5）', () => {
  it('BaseButton：变体/尺寸类与禁用', async () => {
    const wrapper = mount(BaseButton, { props: { variant: 'primary', size: 'sm' } });
    expect(wrapper.classes()).toContain('ff-btn--primary');
    expect(wrapper.classes()).toContain('ff-btn--sm');
    await wrapper.setProps({ disabled: true });
    expect(wrapper.attributes('disabled')).toBeDefined();
  });

  it('BaseSwitch：点击/键盘切换并同步 aria-checked', async () => {
    const wrapper = mount(BaseSwitch, { props: { modelValue: false, label: '深色' } });
    expect(wrapper.attributes('aria-checked')).toBe('false');
    await wrapper.trigger('click');
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual([true]);
    await wrapper.setProps({ modelValue: true });
    expect(wrapper.attributes('aria-checked')).toBe('true');
    await wrapper.trigger('keydown', { key: ' ' });
    expect(wrapper.emitted('update:modelValue')?.[1]).toEqual([false]);
  });

  it('BaseSwitch：禁用时不切换', async () => {
    const wrapper = mount(BaseSwitch, { props: { modelValue: false, disabled: true } });
    await wrapper.trigger('click');
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  });
});

describe('ComponentCard / BaseDrawer（docs/09 P12.5）', () => {
  it('ComponentCard：标题/副标题与具名插槽回退', () => {
    const plain = mount(ComponentCard, {
      props: { title: '库存插件', subtitle: 'example.inventory' },
    });
    expect(plain.text()).toContain('库存插件');
    expect(plain.text()).toContain('example.inventory');
    const slotted = mount(ComponentCard, {
      props: { title: 't' },
      slots: { title: '<b>自定义头</b>', default: '<p>内容</p>' },
    });
    expect(slotted.text()).toContain('自定义头');
    expect(slotted.text()).toContain('内容');
  });

  it('BaseDrawer：open 渲染到 body、Esc 关闭', async () => {
    const wrapper = mount(BaseDrawer, {
      props: { open: false, title: '新建用户' },
      slots: { default: '<p>表单</p>' },
      attachTo: document.body,
    });
    expect(document.querySelector('[data-testid="ff-drawer-overlay"]')).toBeNull();
    await wrapper.setProps({ open: true });
    expect(document.querySelector('[data-testid="ff-drawer-overlay"]')).not.toBeNull();
    expect(document.body.textContent).toContain('新建用户');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await Promise.resolve();
    expect(wrapper.emitted('close')).toHaveLength(1);
    wrapper.unmount();
  });

  it('BaseDrawer：初始 open=true 挂载即监听 Esc（PR #32 审查 P3）', async () => {
    const wrapper = mount(BaseDrawer, {
      props: { open: true, title: '初始打开' },
      attachTo: document.body,
    });
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await Promise.resolve();
    expect(wrapper.emitted('close')).toHaveLength(1);
    wrapper.unmount();
  });
});
