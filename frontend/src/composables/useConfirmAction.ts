import { ref } from 'vue';
import { t } from '@/registry/localeRegistry';

/**
 * 统一确认动作（P16 模式，P19 抽为 composable）：registry record-action 的
 * context.confirm 与详情删除共用；重入时先结算上一个等待者（false）防
 * Promise 悬挂（原 DynamicEntityView 审查 P3-11 行为原样保留）。
 */
export interface ConfirmOptions {
  title?: string;
  confirmLabel?: string;
  danger?: boolean;
}

export interface ConfirmState {
  open: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  danger: boolean;
}

export function useConfirmAction() {
  const confirmState = ref<ConfirmState | null>(null);
  let confirmResolver: ((ok: boolean) => void) | null = null;

  function confirmAction(message: string, options?: ConfirmOptions): Promise<boolean> {
    confirmResolver?.(false);
    confirmState.value = {
      open: true,
      title: options?.title ?? t('common.confirmAction', '确认操作'),
      message,
      confirmLabel: options?.confirmLabel ?? t('common.confirm', '确认'),
      danger: options?.danger ?? false,
    };
    return new Promise((resolve) => {
      confirmResolver = resolve;
    });
  }

  function settleConfirm(ok: boolean): void {
    confirmResolver?.(ok);
    confirmResolver = null;
    confirmState.value = null;
  }

  return { confirmState, confirmAction, settleConfirm };
}
