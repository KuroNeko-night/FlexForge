import { describe, expect, it } from 'vitest';

import { clearSession, saveSession } from '@/auth/token';
import {
  registerRecordAction,
  revokeRecordAction,
  revokeRecordActionsByActivation,
  visibleRecordActions,
} from '@/registry/recordActionRegistry';

describe('record-action registry（extension.record-action 消费面）', () => {
  it('注册/撤销驱动动作列表（响应式），permissionKey 按当前用户过滤', () => {
    clearSession();
    saveSession('t', { id: 1, username: 'u', displayName: 'U', roles: ['USER'] });
    const actions = visibleRecordActions();
    expect(actions.value).toHaveLength(0);

    const extra = registerRecordAction(
      { key: 'record.extra', label: '导出', handler: () => undefined },
      'act-3',
    );
    registerRecordAction({
      key: 'record.admin-only',
      label: '管理',
      permissionKey: 'ADMIN',
      handler: () => undefined,
    });
    expect(actions.value.map((action) => action.key)).toEqual(['record.extra']);

    extra.close();
    expect(actions.value).toHaveLength(0);
    revokeRecordAction('record.admin-only');
    clearSession();
  });

  it('按 activationId 批量撤销（插件停用清理）', () => {
    registerRecordAction({ key: 'p.one', label: 'A', handler: () => undefined }, 'act-5');
    registerRecordAction({ key: 'p.two', label: 'B', handler: () => undefined }, 'act-5');
    expect(revokeRecordActionsByActivation('act-5')).toBe(2);
    expect(visibleRecordActions().value).toHaveLength(0);
  });
});
