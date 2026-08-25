import { ref } from 'vue';

import { fetchEntity } from '@/api/meta';
import type { EntityDetail } from '@/api/types';

/**
 * 元数据加载与版本比对（docs/09 P06：根据实体/视图版本刷新页面）。
 * 模块级版本表记住每个实体最近一次渲染用的 metaVersion；再次加载时版本
 * 递增即标记 changed，调用方进入 stale 状态并重新拉取数据。浏览器刷新后
 * 版本表清空、一切从服务端恢复（验收第 6 条）。
 */
const versions = new Map<string, number>();

export function resetKnownVersions(): void {
  versions.clear();
}

export function useEntityMetadata() {
  const definition = ref<EntityDetail | null>(null);
  const versionChanged = ref(false);

  async function load(name: string): Promise<EntityDetail> {
    const loaded = await fetchEntity(name);
    const known = versions.get(name);
    versionChanged.value = known !== undefined && loaded.metaVersion > known;
    versions.set(name, loaded.metaVersion);
    definition.value = loaded;
    return loaded;
  }

  return { definition, versionChanged, load };
}
