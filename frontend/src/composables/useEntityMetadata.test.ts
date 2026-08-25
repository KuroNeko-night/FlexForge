import { describe, expect, it, vi } from 'vitest';

import { fetchEntity } from '@/api/meta';
import type { EntityDetail } from '@/api/types';
import { resetKnownVersions, useEntityMetadata } from '@/composables/useEntityMetadata';

vi.mock('@/api/meta', () => ({ fetchEntity: vi.fn() }));

function detail(version: number): EntityDetail {
  return {
    id: 'e1',
    name: 'inventory_item',
    displayName: '库存项',
    status: 'enabled',
    pluginId: null,
    updatedAt: '',
    fields: [],
    views: [],
    metaVersion: version,
  };
}

describe('useEntityMetadata（版本比对 → stale 刷新，RB-UI）', () => {
  it('首次加载不视为变更；版本递增标记 changed（浏览器刷新后版本表清空重来）', async () => {
    resetKnownVersions();
    vi.mocked(fetchEntity).mockResolvedValue(detail(1));
    const { versionChanged, load } = useEntityMetadata();
    await load('inventory_item');
    expect(versionChanged.value).toBe(false);

    vi.mocked(fetchEntity).mockResolvedValue(detail(2));
    await load('inventory_item');
    expect(versionChanged.value).toBe(true);

    resetKnownVersions();
    await load('inventory_item');
    expect(versionChanged.value).toBe(false);
  });
});
