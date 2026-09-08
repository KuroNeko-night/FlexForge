// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import ChartCanvas from '@/components/ui/ChartCanvas.vue';

/**
 * 真实构造回归（审查 P1-1）：chart.js v4 裸入口不自动注册控制器——曾经整模块
 * mock 导致"未注册控制器必抛错"对套件不可见。此处不 mock chart.js，仅桩 2d
 * 上下文，验证组件模块加载时的显式 register 让真实 Chart 构造可用。
 */
function stubCanvasContext(): void {
  const proto = HTMLCanvasElement.prototype as unknown as {
    getContext: (type: string) => CanvasRenderingContext2D | null;
  };
  proto.getContext = function getContext(this: HTMLCanvasElement) {
    const state: Record<string, unknown> = { canvas: this };
    return new Proxy(state, {
      get(target, prop) {
        if (prop in target) {
          return target[prop as string];
        }
        return () => 0;
      },
      set(target, prop, value) {
        target[prop as string] = value;
        return true;
      },
    }) as unknown as CanvasRenderingContext2D;
  };
}

describe('ChartCanvas 真实构造（P22 审查 P1-1 回归）', () => {
  it('控制器已注册：真实 Chart 构造不抛错（bar/pie 各一次）', async () => {
    stubCanvasContext();
    const mountProps = {
      title: '采购月度金额合计',
      categories: ['2026-09', '2026-10', '2026-11'],
      values: [18600, 47200.5, 9300],
    };
    const bar = mount(ChartCanvas, { props: { type: 'bar', ...mountProps } });
    await flushPromises();
    expect(bar.find('canvas').exists()).toBe(true);
    bar.unmount();

    const pie = mount(ChartCanvas, { props: { type: 'pie', ...mountProps } });
    await flushPromises();
    expect(pie.find('[data-testid="chart-data"]').text()).toContain('合计');
    pie.unmount();
  });

  it('卸载销毁真实实例不抛错', async () => {
    stubCanvasContext();
    const wrapper = mount(ChartCanvas, {
      props: {
        type: 'bar',
        title: 't',
        categories: ['a'],
        values: [1],
      },
    });
    await flushPromises();
    expect(() => wrapper.unmount()).not.toThrow();
  });

  it('aria 摘要超 5 项时收敛为"等 N 项"（审查 P3-2）', () => {
    stubCanvasContext();
    const categories = Array.from({ length: 7 }, (_, i) => `类目${i + 1}`);
    const wrapper = mount(ChartCanvas, {
      props: { type: 'bar', title: 'T', categories, values: categories.map(() => 1) },
    });
    const label = wrapper.find('canvas').attributes('aria-label');
    expect(label).toContain('类目5 1');
    expect(label).toContain('等 7 项');
    expect(label).not.toContain('类目7');
  });
});
