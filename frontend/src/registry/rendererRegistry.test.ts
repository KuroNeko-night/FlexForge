import { defineComponent } from 'vue';
import { describe, expect, it } from 'vitest';

import BooleanField from '@/components/renderers/BooleanField.vue';
import DateField from '@/components/renderers/DateField.vue';
import DecimalField from '@/components/renderers/DecimalField.vue';
import EnumField from '@/components/renderers/EnumField.vue';
import IntegerField from '@/components/renderers/IntegerField.vue';
import TextField from '@/components/renderers/TextField.vue';
import type { FieldDefinition } from '@/api/types';
import { registerBuiltins, registerRenderer, resolveRenderer } from '@/registry/rendererRegistry';

function fieldOf(rendererId: string): FieldDefinition {
  return {
    id: 'f1',
    name: 'sku',
    displayName: 'SKU',
    fieldType: 'text',
    required: false,
    defaultValue: null,
    validation: null,
    rendererId,
    position: 0,
  };
}

describe('renderer registry（RB-UI：六类内置映射 + 登记即扩展）', () => {
  it('内置六类 renderer 按 FieldTypeRegistry 契约 ID 注册', () => {
    registerBuiltins();
    expect(resolveRenderer(fieldOf('text.default'))).toBe(TextField);
    expect(resolveRenderer(fieldOf('integer.default'))).toBe(IntegerField);
    expect(resolveRenderer(fieldOf('decimal.default'))).toBe(DecimalField);
    expect(resolveRenderer(fieldOf('date.default'))).toBe(DateField);
    expect(resolveRenderer(fieldOf('enum.default'))).toBe(EnumField);
    expect(resolveRenderer(fieldOf('boolean.default'))).toBe(BooleanField);
  });

  it('未知 rendererId 回退 text.default（fail-soft，不白屏）', () => {
    registerBuiltins();
    expect(resolveRenderer(fieldOf('ghost.renderer'))).toBe(TextField);
  });

  it('新增内置 renderer 只需登记映射：注册自定义 ID 即被解析', () => {
    registerBuiltins();
    const custom = defineComponent({ template: '<span>custom</span>' });
    registerRenderer('text.rich', custom);
    expect(resolveRenderer(fieldOf('text.rich'))).toBe(custom);
  });
});
