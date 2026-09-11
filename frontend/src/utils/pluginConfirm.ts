import type { PluginInventoryEntry, PluginPreset } from '@/api/plugins';

import { t } from '@/registry/localeRegistry';

/**
 * 插件页统一确认对话规格（P26 从 PluginsView 拆出，QG-4 行数；P16 统一确认口径）：
 * 卸载/应用预设/删除预设三形态的标题/文案/按钮纯映射。
 */
export type PluginConfirmTarget =
  | { kind: 'uninstall'; plugin: PluginInventoryEntry }
  | { kind: 'apply'; preset: PluginPreset }
  | { kind: 'remove'; preset: PluginPreset };

export interface PluginConfirmSpec {
  title: string;
  message: string;
  confirmLabel: string;
  danger: boolean;
}

export function pluginConfirmSpec(target: PluginConfirmTarget | null): PluginConfirmSpec {
  if (target?.kind === 'uninstall') {
    return {
      title: t('plugins.confirmUninstallTitle', '卸载插件'),
      message: `${t('plugins.confirmUninstallBody', '将卸载')} ${target.plugin.name} · ${target.plugin.pluginId}，${t('plugins.confirmUninstallTail', '注册与实体将一并撤销，审计记录保留。')}`,
      confirmLabel: t('plugins.uninstallBtn', '卸载'),
      danger: true,
    };
  }
  if (target?.kind === 'apply') {
    return {
      title: t('plugins.confirmApplyTitle', '应用预设'),
      message: `${t('plugins.confirmApplyBody', '将把插件状态恢复为预设')}「${target.preset.name}」：${t('plugins.confirmApplyMid', '启用其中')} ${target.preset.entries.length} ${t('plugins.confirmApplyTail', '个插件，并停用其余启用中的插件。')}`,
      confirmLabel: t('plugins.applyBtn', '应用'),
      danger: false,
    };
  }
  if (target?.kind === 'remove') {
    return {
      title: t('plugins.confirmRemoveTitle', '删除预设'),
      message: `${t('plugins.confirmRemoveBody', '将删除预设')}「${target.preset.name}」，${t('plugins.confirmRemoveTail', '不影响当前插件状态。')}`,
      confirmLabel: t('common.delete', '删除'),
      danger: true,
    };
  }
  return { title: '', message: '', confirmLabel: '', danger: false };
}
