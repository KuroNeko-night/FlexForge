import type { PluginInventoryEntry } from '@/api/plugins';

/** PluginsView 测试夹具（P26 拆出，QG-4 行数上限）：双插件清单——启用 gen.iabc123 + 停用 gen.older。 */
export function inventory(): PluginInventoryEntry[] {
  return [
    {
      pluginId: 'gen.iabc123',
      name: '物料库存规格',
      instanceStatus: 'ACTIVE',
      versions: [
        { versionId: 'v1', version: '0.1.2', createdAt: '2026-08-29T02:00:00Z' },
        { versionId: 'v0', version: '0.1.1', createdAt: '2026-08-28T02:00:00Z' },
      ],
      activations: [
        {
          id: 'a2',
          pluginId: 'gen.iabc123',
          pluginVersionId: 'v1',
          operation: 'ACTIVATE',
          status: 'ACTIVE',
          stage: 'REGISTERED',
          errorCode: null,
          requestedBy: 'test-developer',
          startedAt: '2026-08-29T02:00:01Z',
          finishedAt: null,
        },
        {
          id: 'a1',
          pluginId: 'gen.iabc123',
          pluginVersionId: 'v0',
          operation: 'ACTIVATE',
          status: 'FAILED',
          stage: 'DEPENDENCY_CHECK',
          errorCode: 'dependency_missing',
          requestedBy: 'test-admin',
          startedAt: '2026-08-28T02:00:01Z',
          finishedAt: '2026-08-28T02:00:02Z',
        },
      ],
    },
    {
      pluginId: 'gen.older',
      name: '已停用插件',
      instanceStatus: 'imported',
      versions: [{ versionId: 'v9', version: '0.2.0', createdAt: '2026-09-01T02:00:00Z' }],
      activations: [],
    },
  ];
}

export const activeActivation = () => inventory()[0].activations[0];
