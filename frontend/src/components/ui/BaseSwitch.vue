<script setup lang="ts">
/**
 * 基建开关（docs/09 P12.5）：v-model 布尔、键盘可达（role=switch+空格/回车）、
 * knob 位移动画走 --ff-motion-* 令牌（reduced-motion 自动收敛）。
 */
const props = withDefaults(
  defineProps<{ modelValue: boolean; disabled?: boolean; label?: string }>(),
  { disabled: false, label: undefined },
);
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

function toggle(): void {
  if (!props.disabled) {
    emit('update:modelValue', !props.modelValue);
  }
}
function onKeydown(event: KeyboardEvent): void {
  if (event.key === ' ' || event.key === 'Enter') {
    event.preventDefault();
    toggle();
  }
}
</script>

<template>
  <span
    class="ff-switch"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="label"
    :tabindex="disabled ? -1 : 0"
    :disabled="disabled"
    @click="toggle"
    @keydown="onKeydown"
  >
    <span class="ff-switch__track"><span class="ff-switch__knob" /></span>
    <span v-if="label" class="ff-switch__label">{{ label }}</span>
  </span>
</template>
