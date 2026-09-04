// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { EntityDetail, PageResult, RecordView } from '@/api/types';
import { resetKnownVersions } from '@/composables/useEntityMetadata';
import { registerBuiltinContributions } from '@/registry/builtinContributions';
import { registerBuiltins } from '@/registry/rendererRegistry';
import KanbanView from '@/components/KanbanView.vue';
import DynamicEntityView from '@/views/DynamicEntityView.vue';

vi.mock('vue-router', () => ({
  useRoute: () => routeMock,
  useRouter: () => ({ push: pushMock }),
}));
vi.mock('@/api/meta', () => ({ fetchEntity: vi.fn() }));
vi.mock('@/api/data', () => ({
  queryRecords: vi.fn(),
  fetchRecord: vi.fn(),
  createRecord: vi.fn(),
  updateRecord: vi.fn(),
  deleteRecord: vi.fn(),
}));
vi.mock('@/utils/csv', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/utils/csv')>();
  return { ...actual, downloadCsv: vi.fn() };
});

import { fetchEntity } from '@/api/meta';
import { queryRecords, updateRecord } from '@/api/data';
import { downloadCsv } from '@/utils/csv';

const pushMock = vi.fn();
const routeMock: { params: Record<string, string>; name: string } = {
  params: { entity: 'inventory_item' },
  name: 'entity-list',
};

/** P19 拆分文件（行数上限治理）：看板拖拽编排与 CSV 导出的集成用例。 */
function definition(metaVersion: number): EntityDetail {
  return {
    id: 'e1',
    name: 'inventory_item',
    displayName: '库存项',
    status: 'enabled',
    pluginId: null,
    metaVersion,
    fields: [
      {
        id: 'f0',
        name: 'sku',
        displayName: 'SKU',
        fieldType: 'text',
        required: true,
        defaultValue: null,
        validation: null,
        rendererId: 'text.default',
        position: 0,
      },
    ],
    views: [],
  };
}

function definitionWithKanban(): EntityDetail {
  const base = definition(1);
  return {
    ...base,
    fields: [
      ...base.fields,
      {
        id: 'f1',
        name: 'stage',
        displayName: '阶段',
        fieldType: 'enum',
        required: false,
        defaultValue: null,
        validation: { options: ['待办', '已完成'] },
        rendererId: 'enum.default',
        position: 1,
      },
    ],
    views: [
      {
        id: 'v-k',
        viewType: 'kanban',
        name: '看板',
        columns: [{ field: 'sku' }],
        filters: null,
        groupBy: 'stage',
      },
    ],
  };
}

function record(id: string, sku: string): RecordView {
  return { id, entity: 'inventory_item', data: { sku }, createdAt: '', updatedAt: '' };
}

function stagedRecord(id: string, sku: string, stage: string): RecordView {
  return { ...record(id, sku), data: { sku, stage } };
}

function page(items: RecordView[]): PageResult<RecordView> {
  return { items, total: items.length, pageNumber: 1, pageSize: 20 };
}

function mountView() {
  return mount(DynamicEntityView, {
    global: { stubs: { 'router-link': true, teleport: true } },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  resetKnownVersions();
  registerBuiltins();
  registerBuiltinContributions();
  routeMock.params = { entity: 'inventory_item' };
  routeMock.name = 'entity-list';
});

async function mountKanbanBoard(items: RecordView[]) {
  vi.mocked(fetchEntity).mockResolvedValue(definitionWithKanban());
  vi.mocked(queryRecords).mockResolvedValue(page(items));
  const wrapper = mountView();
  await flushPromises();
  await wrapper.find('[data-testid="kanban-toggle"]').trigger('click');
  await flushPromises();
  return wrapper;
}

function moveCard(wrapper: ReturnType<typeof mountView>, recordId: string, option: string) {
  const kanban = wrapper.findComponent(KanbanView);
  const target = kanban.props('records').find((item) => item.id === recordId);
  return kanban.vm.$emit('card-move', target, option);
}

describe('DynamicEntityView：看板拖拽换列编排（P19）', () => {
  it('card-move 以补丁语义 PATCH 分组字段，成功后用服务端响应回填本地记录', async () => {
    const source = stagedRecord('rec-1', 'SKU-1', '待办');
    const wrapper = await mountKanbanBoard([source]);
    vi.mocked(updateRecord).mockResolvedValue({
      ...source,
      data: { ...source.data, stage: '已完成' },
    });
    await moveCard(wrapper, 'rec-1', '已完成');
    await flushPromises();
    expect(updateRecord).toHaveBeenCalledWith('inventory_item', 'rec-1', { stage: '已完成' });
    expect(wrapper.findComponent(KanbanView).props('records')[0]!.data.stage).toBe('已完成');
    expect(wrapper.find('.form-error').exists()).toBe(false);
  });

  it('PATCH 失败回滚原列并给出局部错误提示（整页状态不毁）', async () => {
    const wrapper = await mountKanbanBoard([stagedRecord('rec-1', 'SKU-1', '待办')]);
    vi.mocked(updateRecord).mockRejectedValue(
      new ApiError('permission_denied', '无权限执行该操作', 403, null),
    );
    await moveCard(wrapper, 'rec-1', '已完成');
    await flushPromises();
    expect(wrapper.findComponent(KanbanView).props('records')[0]!.data.stage).toBe('待办');
    expect(wrapper.find('.form-error[role="alert"]').text()).toContain('移动失败');
    expect(wrapper.find('[data-testid="kanban-view"]').exists()).toBe(true);
    expect(wrapper.attributes('data-mode')).toBe('list');
  });
});

/** CSV 用例夹具：含 boolean 字段与仅 sku 可见列的 listView（passed 不导出）。 */
const DEFINITION_WITH_LIST_VIEW: EntityDetail = {
  ...definition(1),
  fields: [
    ...definition(1).fields,
    {
      id: 'f1',
      name: 'passed',
      displayName: '是否通过',
      fieldType: 'boolean',
      required: false,
      defaultValue: null,
      validation: null,
      rendererId: 'boolean.default',
      position: 1,
    },
  ],
  views: [
    {
      id: 'v-l',
      viewType: 'list',
      name: '列表',
      columns: [{ field: 'sku' }],
      filters: null,
      groupBy: null,
    },
  ],
};

describe('DynamicEntityView：CSV 导出（P19）', () => {
  it('点击导出生成当前已加载记录与全部字段列的 CSV（无 listView 时缺省全字段）', async () => {
    vi.mocked(fetchEntity).mockResolvedValue(definition(1));
    vi.mocked(queryRecords).mockResolvedValue(page([record('rec-1', 'SKU-1')]));
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="export-csv"]').trigger('click');
    expect(vi.mocked(downloadCsv)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(downloadCsv).mock.calls[0]).toEqual(['库存项-导出.csv', 'SKU\r\nSKU-1\r\n']);
    // 本地生成：除列表查询外无额外网络调用
    expect(vi.mocked(queryRecords)).toHaveBeenCalledTimes(1);
  });

  it('可见列来自 listView columns（未声明列不导出）', async () => {
    vi.mocked(fetchEntity).mockResolvedValue(DEFINITION_WITH_LIST_VIEW);
    vi.mocked(queryRecords).mockResolvedValue(
      page([
        { ...record('rec-1', 'SKU-1'), data: { sku: 'SKU-1', passed: true } },
        { ...record('rec-2', 'SKU-2'), data: { sku: 'SKU-2', passed: null } },
      ]),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="export-csv"]').trigger('click');
    expect(vi.mocked(downloadCsv).mock.calls[0]![1]).toBe('SKU\r\nSKU-1\r\nSKU-2\r\n');
  });
});
