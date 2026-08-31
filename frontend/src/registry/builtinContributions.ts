import { deleteRecord } from '@/api/data';
import EntityCards from '@/components/EntityCards.vue';
import { registerMenu } from '@/registry/menuRegistry';
import { registerRecordAction, type ActionContext } from '@/registry/recordActionRegistry';
import { registerWidget } from '@/registry/layoutRegistry';

/**
 * 平台内置贡献注册（应用启动装配一次）：内置部件、内置记录动作与本地菜单。
 * 新增内置 renderer/部件/动作 = 在此登记一行映射；插件贡献（P08）经
 * manifest 校验后由 PluginRuntime 调用同一组 register API 接入。
 */
let registered = false;

/** 本地菜单注册（视图路由入口；permissionKey 仅显隐，服务端为边界）。 */
function registerLocalMenus(): void {
  // Issue 工作台入口（P15）：登录即可用（创建/评论/作者 clarify；迁移与生成服务端限 DEVELOPER）
  registerMenu({
    key: 'platform.issues',
    title: 'Issue 工作台',
    route: '/issues',
    order: 20,
  });
  // 插件管理入口（P12）：本地注册菜单，仅 ADMIN 可见（服务端 /plugins/inventory 为边界）
  registerMenu({
    key: 'platform.plugins',
    title: '插件管理',
    route: '/plugins',
    order: 40,
    permissionKey: 'ADMIN',
  });
  // 设置入口（P15）：全员可见（语言等用户级设置迭代 3 加入；AI 配置区仅 ADMIN 渲染）
  registerMenu({
    key: 'platform.settings',
    title: '设置',
    route: '/settings',
    order: 50,
  });
}

/** 内置记录动作（P16：删除经 ActionContext.confirm 统一确认，无原生 confirm）。 */
function registerBuiltinRecordActions(): void {
  registerRecordAction({
    key: 'record.detail',
    label: '详情',
    handler: (record, context: ActionContext) => context.openDetail(record.id),
  });
  registerRecordAction({
    key: 'record.edit',
    label: '编辑',
    handler: (record, context: ActionContext) => context.edit(record.id),
  });
  registerRecordAction({
    key: 'record.delete',
    label: '删除',
    handler: async (record, context: ActionContext) => {
      const ok = await context.confirm('删除后不可恢复，确认删除该记录？', {
        title: '删除记录',
        confirmLabel: '删除',
        danger: true,
      });
      if (!ok) {
        return;
      }
      await deleteRecord(context.entity, record.id);
      await context.refresh();
    },
  });
}

export function registerBuiltinContributions(): void {
  if (registered) {
    return;
  }
  registered = true;
  registerWidget('workbench.entities', EntityCards);
  registerLocalMenus();
  registerBuiltinRecordActions();
}
