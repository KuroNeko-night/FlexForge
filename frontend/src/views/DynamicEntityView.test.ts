// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { EntityDetail, PageResult, RecordView } from '@/api/types';
import { resetKnownVersions } from '@/composables/useEntityMetadata';
import { registerBuiltinContributions } from '@/registry/builtinContributions';
import { registerBuiltins } from '@/registry/rendererRegistry';
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
import { createRecord, deleteRecord, queryRecords } from '@/api/data';

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
