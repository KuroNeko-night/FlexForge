// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { ProcessorEntry, ProcessorResult } from '@/api/processors';
import ToolsView from '@/views/ToolsView.vue';

vi.mock('@/api/processors', () => ({
  fetchProcessors: vi.fn(),
  invokeFileProcessor: vi.fn(),
  downloadProcessorArtifact: vi.fn(),
}));

import { fetchProcessors, invokeFileProcessor } from '@/api/processors';

const listMock = vi.mocked(fetchProcessors);
const invokeMock = vi.mocked(invokeFileProcessor);

const fileTool: ProcessorEntry = {
  key: 'filetools.csv.clean',
  label: 'CSV 清洗',
  pluginId: 'example.filetools',
  inputEntity: '',
  inputMode: 'file',
  accept: ['csv', 'txt'],
  maxInputMB: 5,
};

const entityTool: ProcessorEntry = {
  key: 'analytics.purchase.monthly',
  label: '采购月度透视',
  pluginId: 'example.analytics',
  inputEntity: 'purchase_order',
};

const fileResult: ProcessorResult = {
  kind: 'file',
  artifactId: 'pa-1',
  filename: 'cleaned.csv',
  sizeBytes: 2048,
  contentType: 'text/csv',
  expiresAt: '2026-09-09T12:00:00Z',
};

function mountTools() {
  return mount(ToolsView, { global: { stubs: { teleport: true } } });
}

describe('ToolsView 文件工具页（P23 FR-PLUGIN-14）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('只呈现文件输入处理器；空态/实体模式不进卡片', async () => {
    listMock.mockResolvedValue([fileTool, entityTool]);
    const wrapper = mountTools();
    await flushPromises();
    expect(wrapper.find('[data-testid="tool-filetools.csv.clean"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="tool-analytics.purchase.monthly"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('csv / .txt');
  });

  it('无文件处理器显示空态（NFR-SKEL-01）', async () => {
    listMock.mockResolvedValue([entityTool]);
    const wrapper = mountTools();
    await flushPromises();
    expect(wrapper.find('[data-state="empty"]').exists()).toBe(true);
  });

  it('选文件执行后渲染产物下载卡；失败呈现错误', async () => {
    listMock.mockResolvedValue([fileTool]);
    const wrapper = mountTools();
    await flushPromises();
    const fileInput = wrapper.find('[data-testid="file-input-filetools.csv.clean"]');
    Object.defineProperty(fileInput.element, 'files', {
      value: [new File(['a,b\n1,2'], 'data.csv', { type: 'text/csv' })],
    });
    await fileInput.trigger('change');
    invokeMock.mockResolvedValue(fileResult);
    await wrapper.find('[data-testid="run-file-filetools.csv.clean"]').trigger('click');
    await flushPromises();
    expect(invokeMock).toHaveBeenCalledWith('filetools.csv.clean', expect.any(File));
    expect(wrapper.find('[data-testid="result-file"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('cleaned.csv');

    invokeMock.mockRejectedValue(
      Object.assign(new Error('bad'), { code: 'processor_input_invalid', status: 400 }),
    );
    await wrapper.find('[data-testid="run-file-filetools.csv.clean"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="result-file"]').exists()).toBe(false);
    expect(wrapper.text()).toContain('处理器执行失败');
  });
});
