import { ref, watch } from 'vue';
import type { Ref } from 'vue';

import { fetchProcessors } from '@/api/processors';

/**
 * 实体处理器入口（P20）：实体就绪/切换时拉取该实体声明的数据处理器，
 * 有则前端显示"数据分析"入口；拉取失败静默隐藏（增强不破页）。
 */
export function useEntityProcessors(entity: Ref<string>) {
  const available = ref(false);
  const drawerOpen = ref(false);

  async function load(): Promise<void> {
    if (!entity.value) {
      available.value = false;
      return;
    }
    try {
      available.value = (await fetchProcessors(entity.value)).length > 0;
    } catch {
      available.value = false;
    }
  }

  watch(entity, () => void load(), { immediate: true });
  return { available, drawerOpen };
}
