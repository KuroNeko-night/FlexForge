// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import StateView from '@/components/StateView.vue';

describe('StateView（NFR-UX-01 五状态反馈）', () => {
  it.each(['loading', 'empty', 'error', 'denied', 'stale'] as const)(
    '%s 状态渲染 data-state 与默认文案',
    (state) => {
      const wrapper = mount(StateView, { props: { state } });
      expect(wrapper.attributes('data-state')).toBe(state);
      expect(wrapper.find('.state-message').text()).not.toBe('');
    },
  );

  it('error 携带可诊断 detail（含 requestId）', () => {
    const wrapper = mount(StateView, {
      props: { state: 'error', detail: '必填字段缺失: sku（req-123）' },
    });
    expect(wrapper.find('.state-detail').text()).toContain('req-123');
  });
});
