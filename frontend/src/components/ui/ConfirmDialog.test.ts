// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';

/** P16 统一确认对话框：确认/取消/原因必填/Esc 收敛。teleport stub 入树断言。 */
function mountDialog(props: Record<string, unknown> = {}) {
  return mount(ConfirmDialog, {
    props: {
      open: true,
      title: '删除记录',
      message: '删除后不可恢复',
      ...props,
    },
    global: { stubs: { teleport: true } },
  });
}

describe('ConfirmDialog 基础交互', () => {
  it('确认按钮上抛 confirm，取消按钮上抛 cancel', async () => {
    const wrapper = mountDialog();
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    expect(wrapper.emitted('confirm')?.[0]).toEqual(['']);
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '取消')!
      .trigger('click');
    expect(wrapper.emitted('cancel')).toHaveLength(1);
  });

  it('closed 时不渲染', () => {
    const wrapper = mountDialog({ open: false });
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(false);
  });

  it('busy 时确认不可点', async () => {
    const wrapper = mountDialog({ busy: true });
    const button = wrapper.find('[data-testid="confirm-submit"]').element as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });
});

describe('ConfirmDialog 原因必填', () => {
  it('requireReason 时空原因禁用、填写后携带原因上抛', async () => {
    const wrapper = mountDialog({ requireReason: true });
    const submit = wrapper.find('[data-testid="confirm-submit"]').element as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    await wrapper.find('[data-testid="confirm-reason"]').setValue('需求不完整');
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    expect(wrapper.emitted('confirm')?.[0]).toEqual(['需求不完整']);
  });

  it('Esc 键上抛 cancel', async () => {
    const wrapper = mountDialog();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await flushPromises();
    expect(wrapper.emitted('cancel')).toHaveLength(1);
  });
});
