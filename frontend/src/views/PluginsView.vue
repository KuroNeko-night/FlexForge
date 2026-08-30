<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { ApiError } from '@/api/client';
import {
  activateVersion,
  fetchPluginInventory,
  importPackage,
  stopActivation,
  uninstallPlugin,
  validatePackage,
  type PluginInventoryEntry,
} from '@/api/plugins';
import PluginCard from '@/components/PluginCard.vue';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 插件管理页（docs/09 P12 清单 + P15 写操作）：inventory 视图 + zip 上传
 * （validate→import）/版本激活/停用/卸载（FR-PLUGIN-01/02 消费面，ADMIN；
 * 服务端 @RequireRole 为边界）。卡片渲染与诊断在 PluginCard。
 */
const plugins = ref<PluginInventoryEntry[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);

const fileInput = ref<HTMLInputElement | null>(null);
const pendingFile = ref<File | null>(null);
const importing = ref(false);
const validating = ref(false);
const opError = ref<string | null>(null);
const opNotice = ref<string | null>(null);
const pendingKey = ref<string | null>(null);

async function load(): Promise<void> {
  state.value = 'loading';
  try {
    plugins.value = await fetchPluginInventory();
    state.value = plugins.value.length === 0 ? 'empty' : 'ready';
  } catch (e) {
    if (e instanceof ApiError && e.status === 403) {
      state.value = 'denied';
      return;
    }
    state.value = 'error';
    error.value =
      e instanceof ApiError ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}` : null;
  }
}

function pickFile(event: Event): void {
  const input = event.target as HTMLInputElement;
  pendingFile.value = input.files?.[0] ?? null;
  opError.value = null;
  opNotice.value = null;
}

function describe(e: unknown, fallback: string): string {
  return e instanceof ApiError
    ? `${e.message}${e.requestId ? `（${e.requestId}）` : ''}`
    : fallback;
}

async function submitValidate(): Promise<void> {
  if (validating.value || !pendingFile.value) {
    return;
  }
  validating.value = true;
  pendingKey.value = 'validate';
  opError.value = null;
  opNotice.value = null;
  try {
    const report = await validatePackage(pendingFile.value);
    opNotice.value = report.valid
      ? '校验通过，可以导入'
      : `校验未通过：${report.findings.join('；')}`;
  } catch (e) {
    opError.value = describe(e, '校验失败，请稍后重试');
  } finally {
    validating.value = false;
    pendingKey.value = null;
  }
}

async function submitImport(): Promise<void> {
  if (importing.value || !pendingFile.value) {
    return;
  }
  importing.value = true;
  pendingKey.value = 'import';
  opError.value = null;
  opNotice.value = null;
  try {
    const preview = await importPackage(pendingFile.value);
    opNotice.value = `已导入 ${preview.pluginId}@${preview.version}${
      preview.isNew ? '' : '（幂等命中既有版本）'
    }`;
    pendingFile.value = null;
    if (fileInput.value) {
      fileInput.value.value = '';
    }
    await load();
  } catch (e) {
    opError.value = describe(e, '导入失败，请稍后重试');
  } finally {
    importing.value = false;
    pendingKey.value = null;
  }
}

/** 单飞守卫：同一时刻仅一个写操作在途，键=动作:目标。 */
async function run(key: string, action: () => Promise<unknown>, notice: string): Promise<void> {
  if (pendingKey.value !== null) {
    return;
  }
  pendingKey.value = key;
  opError.value = null;
  opNotice.value = null;
  try {
    await action();
    opNotice.value = notice;
    await load();
  } catch (e) {
    opError.value = describe(e, '操作失败，请稍后重试');
  } finally {
    pendingKey.value = null;
  }
}

function activate(plugin: PluginInventoryEntry, versionId: string, version: string): void {
  void run(`activate:${versionId}`, () => activateVersion(versionId), `已激活 ${plugin.name}@${version}`);
}

function stop(plugin: PluginInventoryEntry): void {
  const activation = plugin.activations.find((item) => item.status === 'ACTIVE');
  if (!activation) {
    return;
  }
  void run(`stop:${activation.id}`, () => stopActivation(activation.id), `已停用 ${plugin.name}`);
}

function uninstall(plugin: PluginInventoryEntry): void {
  if (!window.confirm(`确认卸载 ${plugin.name}（${plugin.pluginId}）？注册与实体将撤销，审计保留。`)) {
    return;
  }
  void run(`uninstall:${plugin.pluginId}`, () => uninstallPlugin(plugin.pluginId), `已卸载 ${plugin.name}`);
}

onMounted(load);
</script>

<template>
  <section class="plugins-view" data-testid="plugins-view">
    <header class="plugins-header">
      <h2>插件管理</h2>
      <BaseButton @click="load">刷新</BaseButton>
    </header>

    <div class="install-bar" data-testid="install-bar">
      <label class="file-label">
        插件包（zip）
        <input
          ref="fileInput"
          type="file"
          accept=".zip,application/zip"
          data-testid="plugin-file"
          @change="pickFile"
        />
      </label>
      <BaseButton :disabled="validating || !pendingFile" @click="submitValidate">
        {{ validating ? '校验中…' : '校验' }}
      </BaseButton>
      <BaseButton
        variant="primary"
        :disabled="importing || !pendingFile"
        data-testid="import-button"
        @click="submitImport"
      >
        {{ importing ? '导入中…' : '导入' }}
      </BaseButton>
    </div>
    <p v-if="opNotice" class="op-notice" role="status">{{ opNotice }}</p>
    <p v-if="opError" class="form-error" role="alert">{{ opError }}</p>

    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">尚无插件导入，可上方上传 zip 导入</p>
    </StateView>
    <ul v-else class="plugin-list">
      <li v-for="plugin in plugins" :key="plugin.pluginId">
        <PluginCard
          :plugin="plugin"
          :pending="pendingKey !== null"
          :has-active="plugin.activations.some((item) => item.status === 'ACTIVE')"
          @activate="(versionId, version) => activate(plugin, versionId, version)"
          @stop="stop(plugin)"
          @uninstall="uninstall(plugin)"
        />
      </li>
    </ul>
  </section>
</template>

<style scoped>
.plugins-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.install-bar {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  padding: var(--ff-space-3);
  background: var(--ff-surface);
  border: 1px solid var(--ff-border-soft);
  border-radius: var(--ff-radius-md);
  flex-wrap: wrap;
}
.file-label {
  display: flex;
  align-items: center;
  gap: var(--ff-space-2);
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.op-notice {
  margin: var(--ff-space-2) 0 0;
  color: var(--ff-primary);
  font-size: var(--ff-text-sm);
}
.plugin-list {
  list-style: none;
  padding: 0;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(24rem, 1fr));
  gap: var(--ff-space-4);
}
</style>
