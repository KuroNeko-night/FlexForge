<script setup lang="ts">
import { computed } from 'vue';

import { resolveLayout, resolveWidget, type ResolvedSlots } from '@/registry/layoutRegistry';

/**
 * 布局渲染器（extension.layout 消费面，FR-PLUGIN-10 验收 4）：按声明渲染
 * target 的槽位与部件编排；无布局贡献时渲染缺省槽位；贡献撤销即时恢复缺省
 * （响应式版本号驱动重算）。部件组件仅来自 widget registry 本地注册（S5）。
 */
const props = defineProps<{
  target: string;
  defaultSlots: ResolvedSlots;
}>();

const slots = computed(() => resolveLayout(props.target, props.defaultSlots).value);

function widgetOf(key: string) {
  return resolveWidget(key);
}
</script>

<template>
  <div class="layout-renderer" :data-layout-target="target">
    <section v-for="slot in slots" :key="slot.name" class="layout-slot" :data-slot="slot.name">
      <component :is="widgetOf(key)" v-for="key in slot.widgetKeys" :key="key" />
    </section>
  </div>
</template>
