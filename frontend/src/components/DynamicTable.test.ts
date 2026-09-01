// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import type { EntityDetail, RecordView } from '@/api/types';
import DynamicTable from '@/components/DynamicTable.vue';
import { registerBuiltins } from '@/registry/rendererRegistry';

function definition(): EntityDetail {
  const types = ['text', 'integer', 'decimal', 'date', 'enum', 'boolean'] as const;
  return {
    id: 'e1',
    name: 'inventory_item',
    displayName: '库存项',
    status: 'enabled',
    pluginId: null,
    updatedAt: '',
    metaVersion: 1,
    fields: types.map((type, index) => ({
      id: `f${index}`,
      name: `${type}_field`,
      displayName: `${type} 列`,
      fieldType: type,
      required: false,
      defaultValue: null,
      validation: null,
      rendererId: `${type}.default`,
      position: index,
    })),
    views: [],
  };
}

const record: RecordView = {
  id: 'rec-1',
  entity: 'inventory_item',
  data: {
    text_field: 'SKU-1',
    integer_field: 7,
    decimal_field: 9.5,
    date_field: '2027-01-01',
    enum_field: 'in_stock',
    boolean_field: true,
  },
  createdAt: '',
  updatedAt: '',
};

describe('DynamicTable（六类字段经 registry 用正确内置 renderer，RB-UI）', () => {
  it('按字段类型渲染六类单元格与列头', () => {
    registerBuiltins();
    const wrapper = mount(DynamicTable, {
      props: { definition: definition(), view: null, records: [record] },
    });
    expect(wrapper.findAll('th').map((th) => th.text())).toEqual([
      'text 列',
      'integer 列',
      'decimal 列',
      'date 列',
      'enum 列',
      'boolean 列',
      '操作',
    ]);
    const cells = wrapper.findAll('tbody td');
    expect(cells[0].text()).toBe('SKU-1');
    expect(cells[1].text()).toBe('7');
    expect(cells[2].text()).toBe('9.5');
    expect(cells[3].text()).toBe('2027-01-01');
    expect(cells[4].text()).toBe('in_stock');
    expect(cells[5].text()).toBe('是');
  });

  it('list 视图 columns 决定列集（visible=false 隐藏）', () => {
    registerBuiltins();
    const wrapper = mount(DynamicTable, {
      props: {
        definition: definition(),
        view: {
          id: 'v1',
          viewType: 'list',
          name: '列',
          columns: [
            { field: 'text_field' },
            { field: 'integer_field', visible: false },
            { field: 'enum_field', visible: true },
          ],
          filters: null,
          groupBy: null,
        },
        records: [record],
      },
    });
    expect(wrapper.findAll('th').map((th) => th.text())).toEqual(['text 列', 'enum 列', '操作']);
  });
});
