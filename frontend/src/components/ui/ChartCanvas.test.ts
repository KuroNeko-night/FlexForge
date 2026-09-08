// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ChartCanvas from '@/components/ui/ChartCanvas.vue';

interface ChartDatasetLike {
  data: number[];
  backgroundColor: string | string[];
}

interface ChartConfigLike {
  type: string;
  data: { labels: string[]; datasets: ChartDatasetLike[] };
  options: {
    plugins: { legend: { display: boolean } };
    animation: false | { duration: number };
  };
}

interface MockChartInstance {
  config: unknown;
  destroy: () => void;
}

// Chart 构造参数捕获（配置映射断言用）；destroy 可追踪卸载清理
vi.mock('chart.js', () => ({
  Chart: vi.fn().mockImplementation(function mockChart(
    this: MockChartInstance,
    _canvas: HTMLCanvasElement,
    config: unknown,
  ) {
    this.config = config;
    this.destroy = vi.fn();
  }),
}));

import { Chart } from 'chart.js';

const chartMock = vi.mocked(Chart);

function stubCanvasContext(): void {
  // happy-dom 默认无 2d 实现，返回哑上下文让组件走真实渲染分支
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({
    dummy: true,
  })) as unknown as typeof HTMLCanvasElement.prototype.getContext;
}

function stubMatchMedia(reduced: boolean): void {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: reduced && query.includes('reduce'),
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        onchange: null,
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  );
}

function mountChart(props: { type?: 'bar' | 'pie' } = {}) {
  return mount(ChartCanvas, {
    props: {
      type: props.type ?? 'bar',
      title: '采购月度金额合计',
      categories: ['2026-09', '2026-10'],
      values: [18600, 47200.5],
    },
  });
}

function lastConfig(): ChartConfigLike {
  const call = chartMock.mock.calls.at(-1);
  return call?.[1] as ChartConfigLike;
}

function setupEnv(): void {
  chartMock.mockClear();
  stubCanvasContext();
  stubMatchMedia(false);
  document.documentElement.style.setProperty('--ff-chart-c1', 'rgb(99, 102, 241)');
}

function teardownEnv(): void {
  vi.restoreAllMocks();
  document.documentElement.style.removeProperty('--ff-chart-c1');
}

describe('ChartCanvas 配置映射（P22，FR-CHART-01）', () => {
  beforeEach(setupEnv);
  afterEach(teardownEnv);

  it('条形图：数据集映射 categories/values，主色取调色板令牌', async () => {
    const wrapper = mountChart();
    await flushPromises();
    expect(chartMock).toHaveBeenCalledTimes(1);
    const config = lastConfig();
    expect(config.type).toBe('bar');
    expect(config.data.labels).toEqual(['2026-09', '2026-10']);
    expect(config.data.datasets[0].data).toEqual([18600, 47200.5]);
    expect(config.data.datasets[0].backgroundColor).toBe('rgb(99, 102, 241)');
    expect(config.options.plugins.legend.display).toBe(false);
    expect(wrapper.find('[data-testid="chart-canvas"]').exists()).toBe(true);
  });

  it('饼图：逐类目取调色板并显示图例', async () => {
    document.documentElement.style.setProperty('--ff-chart-c2', 'rgb(20, 184, 166)');
    mountChart({ type: 'pie' });
    await flushPromises();
    const config = lastConfig();
    expect(config.type).toBe('pie');
    expect(config.data.datasets[0].backgroundColor).toEqual([
      'rgb(99, 102, 241)',
      'rgb(20, 184, 166)',
    ]);
    expect(config.options.plugins.legend.display).toBe(true);
  });

  it('reduced-motion 下动画关闭；常规环境保留动画', async () => {
    stubMatchMedia(true);
    mountChart();
    await flushPromises();
    expect(lastConfig().options.animation).toBe(false);
    stubMatchMedia(false);
    chartMock.mockClear();
    mountChart();
    await flushPromises();
    expect(lastConfig().options.animation).toEqual({ duration: 400 });
  });
});

describe('ChartCanvas 可访问性与生命周期（P22）', () => {
  beforeEach(setupEnv);
  afterEach(teardownEnv);

  it('canvas 带 aria 摘要；数据表逐行列出并给合计（可访问性/核对兜底）', () => {
    const wrapper = mountChart({ type: 'pie' });
    const canvas = wrapper.find('canvas');
    expect(canvas.attributes('role')).toBe('img');
    expect(canvas.attributes('aria-label')).toContain('2026-09 18600');
    expect(canvas.attributes('aria-label')).toContain('2026-10 47200.5');
    const table = wrapper.find('[data-testid="chart-data"]');
    expect(table.text()).toContain('类别');
    expect(table.text()).toContain('合计');
    expect(table.text()).toContain('65800.5');
  });

  it('卸载时销毁 Chart 实例', async () => {
    const wrapper = mountChart();
    await flushPromises();
    const instance = chartMock.mock.results[0]!.value as MockChartInstance;
    const destroySpy = vi.spyOn(instance, 'destroy');
    wrapper.unmount();
    expect(destroySpy).toHaveBeenCalled();
  });
});
