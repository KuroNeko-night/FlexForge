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

import { fetchEntity } from '@/api/meta';
import { createRecord, deleteRecord, queryRecords, updateRecord } from '@/api/data';

const pushMock = vi.fn();
const routeMock: { params: Record<string, string>; name: string } = {
  params: { entity: 'inventory_item' },
  name: 'entity-list',
};

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

/** P17：definition 附带 kanban 视图（groupBy enum 字段）的变体。 */
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

describe('DynamicEntityView（RB-UI 集成面）', () => {
  it('列表模式经 registry 渲染记录', async () => {
    vi.mocked(fetchEntity).mockResolvedValue(definition(1));
    vi.mocked(queryRecords).mockResolvedValue(page([record('rec-1', 'SKU-1')]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.attributes('data-mode')).toBe('list');
    expect(wrapper.find('td[data-field="sku"]').text()).toBe('SKU-1');
  });

  it('403 渲染 denied 状态', async () => {
    vi.mocked(fetchEntity).mockRejectedValue(
      new ApiError('permission_denied', '无权限', 403, null),
    );
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('.state-view[data-state="denied"]').exists()).toBe(true);
  });
});

describe('DynamicEntityView：写路径', () => {
  it('新建模式提交 create 并跳转详情', async () => {
    routeMock.params = { entity: 'inventory_item', id: 'new' };
    routeMock.name = 'entity-new';
    vi.mocked(fetchEntity).mockResolvedValue(definition(1));
    vi.mocked(createRecord).mockResolvedValue(record('rec-9', 'SKU-9'));
    vi.mocked(queryRecords).mockResolvedValue(page([]));
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('input[type="text"]').setValue('SKU-9');
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(createRecord).toHaveBeenCalledWith('inventory_item', {
      sku: 'SKU-9',
    });
    expect(pushMock).toHaveBeenCalledWith({
      name: 'entity-detail',
      params: { entity: 'inventory_item', id: 'rec-9' },
    });
  });

  it('列表删除经统一确认后调用并重载，元数据版本递增出现 stale 提示', async () => {
    vi.mocked(fetchEntity).mockResolvedValueOnce(definition(1)).mockResolvedValue(definition(2));
    vi.mocked(queryRecords).mockResolvedValue(page([record('rec-1', 'SKU-1')]));
    vi.mocked(deleteRecord).mockResolvedValue(undefined);
    const wrapper = mountView();
    await flushPromises();
    const deleteButton = wrapper.findAll('tbody button').find((button) => button.text() === '删除');
    expect(deleteButton).toBeTruthy();
    await deleteButton!.trigger('click');
    await flushPromises();
    // P16：删除先弹统一确认对话框，确认后才调用
    expect(wrapper.find('[data-testid="ff-confirm-overlay"]').exists()).toBe(true);
    expect(deleteRecord).not.toHaveBeenCalled();
    await wrapper.find('[data-testid="confirm-submit"]').trigger('click');
    await flushPromises();
    expect(deleteRecord).toHaveBeenCalledWith('inventory_item', 'rec-1');
    expect(wrapper.find('.stale-note').exists()).toBe(true);
  });
});

describe('DynamicEntityView：看板呈现切换（P17）', () => {
  it('实体声明 kanban 视图时显示切换；默认表格', async () => {
    vi.mocked(fetchEntity).mockResolvedValue(definitionWithKanban());
    vi.mocked(queryRecords).mockResolvedValue(page([record('rec-1', 'SKU-1')]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="view-toggle"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="kanban-view"]').exists()).toBe(false);
  });

  it('无 kanban 视图不显示切换', async () => {
    vi.mocked(fetchEntity).mockResolvedValue(definition(1));
    vi.mocked(queryRecords).mockResolvedValue(page([record('rec-1', 'SKU-1')]));
    const wrapper = mountView();
    await flushPromises();
    expect(wrapper.find('[data-testid="view-toggle"]').exists()).toBe(false);
  });

  it('切看板按 100 条页拉取并渲染分列', async () => {
    vi.mocked(fetchEntity).mockResolvedValue(definitionWithKanban());
    vi.mocked(queryRecords).mockResolvedValue(
      page([record('rec-1', 'SKU-1'), record('rec-2', 'SKU-2')]),
    );
    const wrapper = mountView();
    await flushPromises();
    await wrapper.find('[data-testid="kanban-toggle"]').trigger('click');
    await flushPromises();
    const calls = vi.mocked(queryRecords).mock.calls;
    expect(calls[calls.length - 1][1]).toMatchObject({ pageSize: '100' });
    const board = wrapper.find('[data-testid="kanban-view"]');
    expect(board.exists()).toBe(true);
    expect(board.text()).toContain('SKU-1');
    expect(wrapper.find('table.dynamic-table').exists()).toBe(false);
  });
});

describe('DynamicEntityView：看板拖拽换列编排（P19）', () => {
  function stagedRecord(id: string, sku: string, stage: string): RecordView {
    return { ...record(id, sku), data: { sku, stage } };
  }

  async function mountKanban(items: RecordView[]) {
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

  it('card-move 以补丁语义 PATCH 分组字段，成功后用服务端响应回填本地记录', async () => {
    const source = stagedRecord('rec-1', 'SKU-1', '待办');
    const wrapper = await mountKanban([source]);
    const updated = { ...source, data: { ...source.data, stage: '已完成' } };
    vi.mocked(updateRecord).mockResolvedValue(updated);
    await moveCard(wrapper, 'rec-1', '已完成');
    await flushPromises();
    expect(updateRecord).toHaveBeenCalledWith('inventory_item', 'rec-1', { stage: '已完成' });
    // 服务端响应回填：卡片移入已完成列
    const board = wrapper.findComponent(KanbanView);
    expect(board.props('records')[0]!.data.stage).toBe('已完成');
    expect(wrapper.find('.form-error').exists()).toBe(false);
  });

  it('PATCH 失败回滚原列并给出局部错误提示（整页状态不毁）', async () => {
    const source = stagedRecord('rec-1', 'SKU-1', '待办');
    const wrapper = await mountKanban([source]);
    vi.mocked(updateRecord).mockRejectedValue(
      new ApiError('permission_denied', '无权限执行该操作', 403, null),
    );
    await moveCard(wrapper, 'rec-1', '已完成');
    await flushPromises();
    // 回滚：分组字段回到原值
    const board = wrapper.findComponent(KanbanView);
    expect(board.props('records')[0]!.data.stage).toBe('待办');
    // 局部提示出现，页面仍在看板就绪态
    expect(wrapper.find('.form-error[role="alert"]').text()).toContain('移动失败');
    expect(wrapper.find('[data-testid="kanban-view"]').exists()).toBe(true);
    expect(wrapper.attributes('data-mode')).toBe('list');
  });
});
