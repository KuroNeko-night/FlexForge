import { computed, ref } from 'vue';

import type { RecordView } from '@/api/types';
import { hasRole } from '@/auth/token';
import { KeyedRegistry } from '@/registry/keyed';

/**
 * 记录动作 registry（extension.record-action 前端消费面）：动态表格动作栏的
 * 动作集合 = 内置（详情/编辑/删除）+ 注册贡献（P09 起插件经 manifest 声明、
 * handlerId 必须是平台内置 ID）。permissionKey 仅做前端显隐（体验层），
 * 安全边界在服务端（S2）。注册/撤销即时生效（响应式版本号）。
 */
export interface ActionContext {
  entity: string;
  openDetail(id: string): Promise<void>;
  edit(id: string): Promise<void>;
  refresh(): Promise<void>;
  /**
   * 统一确认（P16）：破坏性动作经消费视图的确认对话框异步确认，
   * registry/动作保持声明式、不直接依赖 DOM（原生 confirm 不再出现）。
   */
  confirm(
    message: string,
    options?: { title?: string; confirmLabel?: string; danger?: boolean },
  ): Promise<boolean>;
}

export interface RecordAction {
  key: string;
  label: string;
  permissionKey?: string | null;
  handler: (record: RecordView, context: ActionContext) => void | Promise<void>;
}

const registry = new KeyedRegistry<RecordAction>();
const version = ref(0);

function bump(): void {
  version.value += 1;
}

export function registerRecordAction(action: RecordAction, activationId: string | null = null) {
  const registration = registry.register(action.key, action, activationId);
  bump();
  return {
    close: () => {
      registration.close();
      bump();
    },
  };
}

export function revokeRecordAction(key: string): void {
  registry.revoke(key);
  bump();
}

export function revokeRecordActionsByActivation(activationId: string): number {
  const removed = registry.revokeByActivation(activationId);
  if (removed > 0) {
    bump();
  }
  return removed;
}

/** 当前用户可见的动作列表（permissionKey 过滤；注册序稳定；响应式重算）。 */
export function visibleRecordActions() {
  return computed<RecordAction[]>(() => {
    void version.value;
    return registry
      .list()
      .map((entry) => entry.value)
      .filter((action) => !action.permissionKey || hasRole(action.permissionKey));
  });
}
