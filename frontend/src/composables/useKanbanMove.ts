import { ref } from 'vue';
import type { Ref } from 'vue';

import { updateRecord } from '@/api/data';
import { apiErrorMessage } from '@/api/client';
import type { RecordView, ViewDefinition } from '@/api/types';

/**
 * 看板拖拽换列编排（P19，P20 抽 composable）：乐观移动 → PATCH 分组字段（补丁
 * 语义）→ 服务端响应回填本地记录；失败回滚原列并局部提示（页面状态不毁）；
 * 同卡片在途未结算时忽略新拖拽（防旧回滚覆盖新乐观值）。
 */
export function useKanbanMove(
  entity: Ref<string>,
  kanbanView: Ref<ViewDefinition | null>,
  records: Ref<RecordView[]>,
) {
  const moveError = ref<string | null>(null);
  const movingIds = new Set<string>();

  async function onCardMove(record: RecordView, targetOption: string): Promise<void> {
    const groupBy = kanbanView.value?.groupBy;
    if (!groupBy || movingIds.has(record.id)) {
      return;
    }
    movingIds.add(record.id);
    const previous = record.data[groupBy];
    record.data[groupBy] = targetOption;
    moveError.value = null;
    try {
      const updated = await updateRecord(entity.value, record.id, { [groupBy]: targetOption });
      const index = records.value.findIndex((item) => item.id === record.id);
      if (index >= 0) {
        records.value[index] = updated;
      }
    } catch (e) {
      record.data[groupBy] = previous;
      const detail = apiErrorMessage(e, null);
      moveError.value = detail ? `移动失败，已还原到原列：${detail}` : '移动失败，已还原到原列';
    } finally {
      movingIds.delete(record.id);
    }
  }

  return { moveError, onCardMove };
}
