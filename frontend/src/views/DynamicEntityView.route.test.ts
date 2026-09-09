// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';

// 回归背景（P23 缺陷）：既有测试整套 mock vue-router 并凭空注入 params.id='new'，
// 而真实路由对静态段 data/:entity/new 不产出 params.id——新增表单在真实浏览器
// 自 P06 起从未打开过，mock 测试却全绿。本文件走真实 createRouter 匹配。
vi.mock('@/api/meta', () => ({ fetchEntity: vi.fn() }));
vi.mock('@/api/data', () => ({
  queryRecords: vi.fn(),
  fetchRecord: vi.fn(),
  createRecord: vi.fn(),
  updateRecord: vi.fn(),
  deleteRecord: vi.fn(),
}));
vi.mock('@/api/processors', () => ({ fetchProcessors: vi.fn() }));

import { fetchEntity } from '@/api/meta';
import { queryRecords } from '@/api/data';
import { fetchProcessors } from '@/api/processors';
import type { EntityDetail, PageResult, RecordView } from '@/api/types';
import { resetKnownVersions } from '@/composables/useEntityMetadata';
import { registerBuiltinContributions } from '@/registry/builtinContributions';
import { registerBuiltins } from '@/registry/rendererRegistry';
import DynamicEntityView from '@/views/DynamicEntityView.vue';

function definition(): EntityDetail {
  return {
    id: 'e1',
    name: 'inventory_item',
    displayName: '库存项',
    status: 'enabled',
    pluginId: null,
    metaVersion: 1,
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

function emptyPage(): PageResult<RecordView> {
  return { items: [], total: 0, pageNumber: 1, pageSize: 20 };
}

function buildRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/data/:entity', name: 'entity-list', component: DynamicEntityView },
      { path: '/data/:entity/new', name: 'entity-new', component: DynamicEntityView },
      { path: '/data/:entity/:id', name: 'entity-detail', component: DynamicEntityView },
      { path: '/data/:entity/:id/edit', name: 'entity-edit', component: DynamicEntityView },
    ],
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  resetKnownVersions();
  registerBuiltins();
  registerBuiltinContributions();
  vi.mocked(fetchEntity).mockResolvedValue(definition());
  vi.mocked(queryRecords).mockResolvedValue(emptyPage());
  vi.mocked(fetchProcessors).mockResolvedValue([]);
});

describe('DynamicEntityView 真实路由匹配（静态段 new 不产 params.id）', () => {
  it('列表→/new 导航后渲染创建表单（data-mode=new）', async () => {
    const router = buildRouter();
    await router.push('/data/inventory_item');
    await router.isReady();
    const wrapper = mount(DynamicEntityView, {
      global: { plugins: [router], stubs: { teleport: true } },
    });
    await flushPromises();
    expect(wrapper.attributes('data-mode')).toBe('list');

    await router.push('/data/inventory_item/new');
    await flushPromises();
    expect(wrapper.attributes('data-mode')).toBe('new');
    expect(wrapper.find('form').exists()).toBe(true);
    expect(wrapper.find('button[type="submit"], form button').exists()).toBe(true);
  });
});
