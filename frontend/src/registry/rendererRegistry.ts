import type { Component } from 'vue';

import BooleanField from '@/components/renderers/BooleanField.vue';
import DateField from '@/components/renderers/DateField.vue';
import DecimalField from '@/components/renderers/DecimalField.vue';
import EnumField from '@/components/renderers/EnumField.vue';
import IntegerField from '@/components/renderers/IntegerField.vue';
import TextField from '@/components/renderers/TextField.vue';
import type { FieldDefinition } from '@/api/types';
import { KeyedRegistry } from '@/registry/keyed';

/**
 * 字段 renderer registry（extension.field-renderer 前端侧，docs/08 §6）：
 * key = 平台内置 renderer ID（FieldTypeRegistry 契约 `<type>.default`×6）。
 * 新增内置 renderer = 在 registerBuiltins 增加一行映射，动态列表/表单通用组件
 * 不改（docs/09 P06 验收）；插件贡献 renderer P08 经注册表接入。
 */
const registry = new KeyedRegistry<Component>();

let builtinsRegistered = false;

export function registerBuiltins(): void {
  if (builtinsRegistered) {
    return;
  }
  builtinsRegistered = true;
  registry.register('text.default', TextField);
  registry.register('integer.default', IntegerField);
  registry.register('decimal.default', DecimalField);
  registry.register('date.default', DateField);
  registry.register('enum.default', EnumField);
  registry.register('boolean.default', BooleanField);
}

export function registerRenderer(id: string, component: Component): void {
  registry.register(id, component);
}

export function resolveRenderer(field: FieldDefinition): Component {
  // 声明 ID 命中即用；悬空/缺失（插件实体字段注册不带 rendererId，P08 起存量）
  // 按 fieldType 回退 <type>.default（FieldTypeRegistry 六类默认契约）；
  // 未知类型最终回退 text——动态列表/表单保持可渲染，不因悬空引用整页失败
  const declared = field.rendererId ? registry.resolve(field.rendererId) : undefined;
  return declared ?? registry.resolve(`${field.fieldType}.default`) ?? TextField;
}

export function rendererIds(): string[] {
  return registry.list().map((entry) => entry.key);
}
