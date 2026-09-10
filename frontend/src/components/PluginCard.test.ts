// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import type { PluginInventoryEntry } from '@/api/plugins';
import PluginCard from '@/components/PluginCard.vue';

/** P24 降噪：失败行只在"晚于最近成功激活"或"从未成功激活"时呈现。 */

function entry(activations: PluginInventoryEntry['activations']): PluginInventoryEntry {
  return {
    pluginId: 'demo.plugin',
    name: '演示插件',
    instanceStatus: 'ACTIVE',
    versions: [{ versionId: 'v1', version: '0.1.0', createdAt: '2026-09-10T00:00:00Z' }],
    activations,
  };
}

const failed = (id: string, startedAt: string) => ({
  id,
  pluginId: 'demo.plugin',
  pluginVersionId: 'v1',
  operation: 'ACTIVATE',
  status: 'FAILED',
  stage: 'MIGRATION',
  errorCode: 'migration_failed',
  requestedBy: 'admin',
  startedAt,
  finishedAt: startedAt,
});

const active = (id: string, startedAt: string) => ({
  id,
  pluginId: 'demo.plugin',
  pluginVersionId: 'v1',
  operation: 'ACTIVATE',
  status: 'ACTIVE',
  stage: 'REGISTERED',
  errorCode: null,
  requestedBy: 'admin',
  startedAt,
  finishedAt: null,
});

describe('PluginCard 失败行降噪（P24）', () => {
  it('成功激活晚于失败：陈旧失败行不呈现', () => {
    const wrapper = mount(PluginCard, {
      props: { plugin: entry([active('a2', '2026-09-10T02:00:00Z'), failed('a1', '2026-09-09T02:00:00Z')]), pending: false },
    });
    expect(wrapper.find('[data-testid="plugin-last-failure"]').exists()).toBe(false);
  });

  it('失败晚于成功激活（如升级失败补偿回旧版）：诊断行保留', () => {
    const wrapper = mount(PluginCard, {
      props: { plugin: entry([failed('a2', '2026-09-10T02:00:00Z'), active('a1', '2026-09-09T02:00:00Z')]), pending: false },
    });
    expect(wrapper.find('[data-testid="plugin-last-failure"]').text()).toContain(
      '最近激活失败于 MIGRATION · migration_failed',
    );
  });

  it('从未成功激活：最近失败行保留（停用排障入口）', () => {
    const wrapper = mount(PluginCard, {
      props: { plugin: entry([failed('a1', '2026-09-09T02:00:00Z')]), pending: false },
    });
    expect(wrapper.find('[data-testid="plugin-last-failure"]').exists()).toBe(true);
  });
});
