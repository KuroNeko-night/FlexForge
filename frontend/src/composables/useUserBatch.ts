import { computed, ref, type Ref } from 'vue';

import { ApiError } from '@/api/client';
import { batchUpdateStatus } from '@/api/system';
import { t } from '@/registry/localeRegistry';

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
  const clearFeedback = (): void => {
    batchError.value = null;
    batchNotice.value = null;
  };

  const { isSelected, toggle, setSelection, prune, clear } = selectionOps(
    selectedIds,
    clearFeedback,
  );

  function run(status: 'ACTIVE' | 'BLOCKED'): Promise<void> {
    return runBatch(status, {
      selectedIds,
      running,
      batchError,
      batchNotice,
      hasSelection,
      clear,
      reload,
    });
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

/** 选择集操作族（从 useUserBatch 拆出守 50 行上限）。 */
function selectionOps(
  selectedIds: Ref<number[]>,
  onChanged: () => void,
): {
  isSelected: (id: number) => boolean;
  toggle: (id: number) => void;
  setSelection: (ids: number[]) => void;
  prune: (validIds: number[]) => void;
  clear: () => void;
} {
  const isSelected = (id: number): boolean => selectedIds.value.includes(id);
  const toggle = (id: number): void => {
    selectedIds.value = isSelected(id)
      ? selectedIds.value.filter((item) => item !== id)
      : [...selectedIds.value, id];
    onChanged();
  };
  const setSelection = (ids: number[]): void => {
    selectedIds.value = ids;
  };
  /** 列表重载后剔除已不存在的选择（防陈旧 id 触发整批 404）。 */
  const prune = (validIds: number[]): void => {
    selectedIds.value = selectedIds.value.filter((id) => validIds.includes(id));
  };
  const clear = (): void => {
    selectedIds.value = [];
  };
  return { isSelected, toggle, setSelection, prune, clear };
}

/** 单请求批量提交（从 useUserBatch 拆出守 50 行上限）：成功清选+重载，失败保留选择。 */
async function runBatch(
  status: 'ACTIVE' | 'BLOCKED',
  ctx: {
    selectedIds: Ref<number[]>;
    running: Ref<boolean>;
    batchError: Ref<string | null>;
    batchNotice: Ref<string | null>;
    hasSelection: Ref<boolean>;
    clear: () => void;
    reload: () => Promise<unknown>;
  },
): Promise<void> {
  if (!ctx.hasSelection.value || ctx.running.value) {
    return;
  }
  ctx.running.value = true;
  ctx.batchError.value = null;
  ctx.batchNotice.value = null;
  try {
    const updated = await batchUpdateStatus([...ctx.selectedIds.value], status);
    ctx.batchNotice.value = `${t('common.donePrefix', '已')}${status === 'BLOCKED' ? t('plugins.stopAction', '停用') : t('plugins.enableAction', '启用')} ${updated.length} 个账号`;
    ctx.clear();
    await ctx.reload();
  } catch (e) {
    ctx.batchError.value =
      e instanceof ApiError ? e.message : t('users.batchFailed', '批量操作失败，请稍后重试');
  } finally {
    ctx.running.value = false;
  }
}
