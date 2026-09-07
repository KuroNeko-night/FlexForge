// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import type { PluginPreset } from '@/api/plugins';
import PluginPresetBar from '@/components/PluginPresetBar.vue';

const preset: PluginPreset = {
  id: 'preset-1',
  name: '生产演示',
  entries: [{ pluginId: 'gen.iabc123', versionId: 'v1', version: '0.1.2' }],
  createdBy: 'test-admin',
  createdAt: '2026-09-07T02:00:00Z',
};

function mountBar(presets: PluginPreset[], busy = false) {
  return mount(PluginPresetBar, { props: { presets, busy } });
}

describe('PluginPresetBar 预设条（FR-PLUGIN-12）', () => {
  it('渲染预设名与条目数，空清单显示空态', () => {
    const empty = mountBar([]);
    expect(empty.text()).toContain('尚无预设');
    const filled = mountBar([preset]);
    expect(filled.text()).toContain('生产演示');
    expect(filled.text()).toContain('1 个插件');
  });

  it('输入名称后保存：上抛 save 事件并清空输入', async () => {
    const wrapper = mountBar([]);
    await wrapper.find('[data-testid="preset-name-input"]').setValue('  看板演示  ');
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存当前为预设')!
      .trigger('click');
    expect(wrapper.emitted('save')?.[0]).toEqual(['看板演示']);
    expect(
      (wrapper.find('[data-testid="preset-name-input"]').element as HTMLInputElement).value,
    ).toBe('');
  });

  it('空名不可保存；busy 时禁用全部操作', async () => {
    const wrapper = mountBar([preset], true);
    const buttons = wrapper.findAll('button');
    for (const button of buttons) {
      expect((button.element as HTMLButtonElement).disabled).toBe(true);
    }
    expect(wrapper.emitted('save')).toBeUndefined();
  });

  it('应用与删除上抛对应预设', async () => {
    const wrapper = mountBar([preset]);
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '应用')!
      .trigger('click');
    expect(wrapper.emitted('apply')?.[0]).toEqual([preset]);
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '删除')!
      .trigger('click');
    expect(wrapper.emitted('remove')?.[0]).toEqual([preset]);
  });
});
