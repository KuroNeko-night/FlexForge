import { ref, type Ref } from 'vue';

import { apiErrorMessage } from '@/api/client';
import {
  applyPreset,
  deletePreset,
  fetchPresets,
  savePreset,
  type PluginPreset,
  type PresetApplyResult,
} from '@/api/plugins';

/**
 * 插件预设状态与操作（P21，FR-PLUGIN-12）：保存/应用/删除 + 结果逐项呈现。
 * 单飞守卫与错误归一在 presetOperations；确认对话框与清单刷新回调由视图注入。
 */
export function usePluginPresets(reloadInventory: () => Promise<unknown>) {
  const state: PresetState = {
    presets: ref([]),
    busy: ref(false),
    notice: ref(null),
    error: ref(null),
    failures: ref([]),
  };
  return { ...state, ...presetOperations(state, reloadInventory) };
}

interface PresetState {
  presets: Ref<PluginPreset[]>;
  busy: Ref<boolean>;
  notice: Ref<string | null>;
  error: Ref<string | null>;
  failures: Ref<PresetApplyResult['failed']>;
}

/** 预设操作集：单飞（任一在途时忽略后续触发），结果文案由 action 返回。 */
function presetOperations(state: PresetState, reloadInventory: () => Promise<unknown>) {
  async function load(): Promise<void> {
    state.presets.value = await fetchPresets();
  }

  async function run(action: () => Promise<string>): Promise<void> {
    if (state.busy.value) {
      return;
    }
    state.busy.value = true;
    state.notice.value = null;
    state.error.value = null;
    state.failures.value = [];
    try {
      state.notice.value = await action();
      await Promise.all([load(), reloadInventory()]);
    } catch (e) {
      state.error.value = apiErrorMessage(e, '预设操作失败，请稍后重试');
    } finally {
      state.busy.value = false;
    }
  }

  function save(name: string): void {
    void run(async () => {
      await savePreset(name);
      return `已保存预设 ${name}`;
    });
  }

  function apply(preset: PluginPreset): void {
    void run(async () => {
      const result = await applyPreset(preset.id);
      state.failures.value = result.failed;
      return applySummary(preset, result);
    });
  }

  function remove(preset: PluginPreset): void {
    void run(async () => {
      await deletePreset(preset.id);
      return `已删除预设 ${preset.name}`;
    });
  }

  return { load, save, apply, remove };
}

/** 应用结果摘要：启用/停用逐项列举，失败明细由 failures 单独渲染。 */
function applySummary(preset: PluginPreset, result: PresetApplyResult): string {
  const parts: string[] = [];
  if (result.activated.length > 0) {
    parts.push(`启用 ${result.activated.join('、')}`);
  }
  if (result.stopped.length > 0) {
    parts.push(`停用 ${result.stopped.join('、')}`);
  }
  const detail = parts.length > 0 ? `：${parts.join('；')}` : '';
  return `已应用预设 ${preset.name}${detail}`;
}
