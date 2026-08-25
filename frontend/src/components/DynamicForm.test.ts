// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import type { EntityDetail } from '@/api/types';
import DynamicForm from '@/components/DynamicForm.vue';
import { registerBuiltins } from '@/registry/rendererRegistry';

function definition(): EntityDetail {
  return {
    id: 'e1',
    name: 'inventory_item',
    displayName: '库存项',
    status: 'enabled',
    pluginId: null,
    updatedAt: '',
    metaVersion: 1,
    fields: [
      {
        id: 'f0',
        name: 'sku',
        displayName: 'SKU',
        fieldType: 'text',
        required: true,
        defaultValue: null,
        validation: null,
        rendererId: 'text.default',
        position: 1,
      },
      {
        id: 'f1',
        name: 'qty',
        displayName: '数量',
        fieldType: 'integer',
        required: false,
        defaultValue: 0,
        validation: { min: 0 },
        rendererId: 'integer.default',
        position: 0,
      },
      {
        id: 'f2',
        name: 'status',
        displayName: '状态',
        fieldType: 'enum',
        required: false,
        defaultValue: 'in_stock',
        validation: { options: ['in_stock', 'sold_out'] },
        rendererId: 'enum.default',
        position: 2,
      },
    ],
    views: [],
  };
}

describe('DynamicForm（默认值补齐 + 提交载荷清洗）', () => {
  it('渲染全部字段（position 序），必填标记与默认值可见', () => {
    registerBuiltins();
    const wrapper = mount(DynamicForm, {
      props: {
        definition: definition(),
        view: null,
        initial: null,
        submitLabel: '创建',
        submitting: false,
      },
    });
    expect(wrapper.findAll('label').map((label) => label.text())).toEqual(['数量', 'SKU*', '状态']);
    expect(wrapper.find('input[type="number"]').element).toBeTruthy();
    expect((wrapper.find('select').element as HTMLSelectElement).value).toBe('in_stock');
  });

  it('提交携带全部已定义键（null=显式清除语义）', async () => {
    registerBuiltins();
    const wrapper = mount(DynamicForm, {
      props: {
        definition: definition(),
        view: null,
        initial: null,
        submitLabel: '创建',
        submitting: false,
      },
    });
    const textInput = wrapper.find('input[type="text"]');
    await textInput.setValue('SKU-9');
    await wrapper.find('form').trigger('submit');
    const emitted = wrapper.emitted('submit');
    expect(emitted).toBeTruthy();
    expect(emitted![0][0]).toEqual({ qty: 0, sku: 'SKU-9', status: 'in_stock' });

    // 清空文本输入 → 空值以 null 提交（后端 PATCH null 清键 / create 丢弃 null）
    await textInput.setValue('');
    await textInput.trigger('input');
    const empty = wrapper.find('input[type="text"]').element as HTMLInputElement;
    empty.value = '';
    empty.dispatchEvent(new Event('input'));
    await wrapper.find('form').trigger('submit');
    const cleared = wrapper.emitted('submit');
    expect((cleared!.at(-1)?.[0] as Record<string, unknown>).sku).toBeNull();
  });
});
