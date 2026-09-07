// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { ProcessorEntry, ProcessorResult } from '@/api/processors';
import ProcessorDrawer from '@/components/ProcessorDrawer.vue';

vi.mock('@/api/processors', () => ({
  fetchProcessors: vi.fn(),
  invokeProcessor: vi.fn(),
}));

import { fetchProcessors, invokeProcessor } from '@/api/processors';

const entries: ProcessorEntry[] = [
  {
    key: 'analytics.purchase.monthly',
    label: '采购月度透视',
    pluginId: 'example.analytics',
    inputEntity: 'purchase_order',
  },
  {
    key: 'analytics.quality.yield',
    label: '检验合格率统计',
    pluginId: 'example.analytics',
    inputEntity: 'quality_inspection',
  },
];

function mountDrawer() {
  return mount(ProcessorDrawer, {
    props: { open: true, entity: 'purchase_order' },
    global: { stubs: { teleport: true } },
  });
}

/** table 结果夹具（月度透视样例）。 */
const TABLE_RESULT: ProcessorResult = {
  kind: 'table',
  columns: [
    { name: 'supplier', label: '供应商' },
    { name: '2026-09', label: '2026-09' },
  ],
  rows: [
    ['华东精密配件', 18600.0],
    ['南方铝业', 0.0],
  ],
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(fetchProcessors).mockResolvedValue(entries);
});

describe('ProcessorDrawer：清单与 table 结果（P20）', () => {
  it('打开时拉取该实体声明的处理器并渲染清单', async () => {
    const wrapper = mountDrawer();
    await flushPromises();
    expect(fetchProcessors).toHaveBeenCalledWith('purchase_order');
    const labels = wrapper.findAll('.processor-label').map((n) => n.text());
    expect(labels).toEqual(['采购月度透视', '检验合格率统计']);
  });

  it('执行 table 处理器渲染结构表（列头与单元格）', async () => {
    vi.mocked(invokeProcessor).mockResolvedValue(TABLE_RESULT);
    const wrapper = mountDrawer();
    await flushPromises();
    await wrapper.find('[data-testid="run-analytics.purchase.monthly"]').trigger('click');
    await flushPromises();
    expect(invokeProcessor).toHaveBeenCalledWith('analytics.purchase.monthly', 'purchase_order');
    const result = wrapper.find('[data-testid="processor-result"]');
    expect(result.exists()).toBe(true);
    expect(wrapper.findAll('th').map((n) => n.text())).toEqual(['供应商', '2026-09']);
    expect(wrapper.findAll('tbody tr')[0]!.text()).toContain('华东精密配件');
    expect(wrapper.findAll('tbody tr')[0]!.text()).toContain('18600');
  });
});

describe('ProcessorDrawer：summary 结果与失败路径（P20）', () => {
  it('执行 summary 处理器渲染指标列表', async () => {
    const summary: ProcessorResult = {
      kind: 'summary',
      items: [
        { label: '计划数量合计', value: 300 },
        { label: '最高负载班组', value: '注塑班组（200 件）' },
      ],
    };
    vi.mocked(invokeProcessor).mockResolvedValue(summary);
    const wrapper = mountDrawer();
    await flushPromises();
    await wrapper.find('[data-testid="run-analytics.quality.yield"]').trigger('click');
    await flushPromises();
    const summaryBox = wrapper.find('.processor-summary');
    expect(summaryBox.text()).toContain('计划数量合计');
    expect(summaryBox.text()).toContain('注塑班组（200 件）');
  });

  it('执行失败显示局部错误（处理器失败不毁抽屉）', async () => {
    vi.mocked(invokeProcessor).mockRejectedValue(
      new ApiError('processor_failed', '处理器执行超时（上限 10 秒）', 500, null),
    );
    const wrapper = mountDrawer();
    await flushPromises();
    await wrapper.find('[data-testid="run-analytics.purchase.monthly"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('.form-error[role="alert"]').text()).toContain('处理器执行超时');
    expect(wrapper.find('.processor-list').exists()).toBe(true);
  });
});
