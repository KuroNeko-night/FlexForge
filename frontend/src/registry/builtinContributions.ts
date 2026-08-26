import { deleteRecord } from '@/api/data';
import EntityCards from '@/components/EntityCards.vue';
import { registerRecordAction, type ActionContext } from '@/registry/recordActionRegistry';
import { registerWidget } from '@/registry/layoutRegistry';

/**
 * 平台内置贡献注册（应用启动装配一次）：内置部件与内置记录动作。
 * 新增内置 renderer/部件/动作 = 在此登记一行映射；插件贡献（P08）经
 * manifest 校验后由 PluginRuntime 调用同一组 register API 接入。
 */
let registered = false;

export function registerBuiltinContributions(): void {
  if (registered) {
    return;
  }
  registered = true;
  registerWidget('workbench.entities', EntityCards);
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
      if (!window.confirm('确认删除该记录？')) {
        return;
      }
      await deleteRecord(context.entity, record.id);
      await context.refresh();
    },
  });
}
