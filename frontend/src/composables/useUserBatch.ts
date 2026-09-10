import { computed, ref } from 'vue';

import { ApiError } from '@/api/client';
import { batchUpdateStatus } from '@/api/system';

/**
 * 用户批量停启用状态与执行（P24，FR-AUTH-05）：选择集管理与单请求批量提交；
 * 确认对话/批量条 UI 在 UserBatchBar（本组合式只持状态，供 UsersView 与测试复用）。
 * 失败保留选择（可调整后重试）；成功清选并触发列表重载。
 */
export function useUserBatch(reload: () => Promise<unknown>) {
  const selectedIds = ref<number[]>([]);
  const running = ref(false);
  const batchError = ref<string | null>(null);
  const batchNotice = ref<string | null>(null);

  const hasSelection = computed(() => selectedIds.value.length > 0);

  function isSelected(id: number): boolean {
    return selectedIds.value.includes(id);
  }

  function toggle(id: number): void {
    selectedIds.value = isSelected(id)
      ? selectedIds.value.filter((item) => item !== id)
      : [...selectedIds.value, id];
    batchError.value = null;
    batchNotice.value = null;
  }

  function setSelection(ids: number[]): void {
    selectedIds.value = ids;
  }

  /** 列表重载后剔除已不存在的选择（防陈旧 id 触发整批 404）。 */
  function prune(validIds: number[]): void {
    selectedIds.value = selectedIds.value.filter((id) => validIds.includes(id));
  }

  function clear(): void {
    selectedIds.value = [];
  }

  async function run(status: 'ACTIVE' | 'BLOCKED'): Promise<void> {
    if (!hasSelection.value || running.value) {
      return;
    }
    running.value = true;
    batchError.value = null;
    batchNotice.value = null;
    try {
      const updated = await batchUpdateStatus([...selectedIds.value], status);
      batchNotice.value = `已${status === 'BLOCKED' ? '停用' : '启用'} ${updated.length} 个账号`;
      clear();
      await reload();
    } catch (e) {
      batchError.value = e instanceof ApiError ? e.message : '批量操作失败，请稍后重试';
    } finally {
      running.value = false;
    }
  }

  return {
    selectedIds,
    hasSelection,
    running,
    batchError,
    batchNotice,
    isSelected,
    toggle,
    setSelection,
    prune,
    clear,
    run,
  };
}
