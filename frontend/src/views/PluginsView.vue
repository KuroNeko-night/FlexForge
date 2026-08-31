<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
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
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import UploadDropzone from '@/components/UploadDropzone.vue';
import { t } from '@/registry/localeRegistry';

/**
 * 插件管理页（docs/09 P12 清单 + P15 写操作，P16 交互重构）：inventory 视图
 * + 上传区（选包自动校验，通过才可导入）/版本激活/停用/卸载（FR-PLUGIN-01/02
 * 消费面，ADMIN；服务端 @RequireRole 为边界）。卡片渲染与诊断在 PluginCard，
 * 上传区交互在 UploadDropzone。
 */
const plugins = ref<PluginInventoryEntry[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);

const pendingFile = ref<File | null>(null);
const check = ref<'idle' | 'checking' | 'passed' | 'failed'>('idle');
const findings = ref<string[]>([]);
const importing = ref(false);
const opError = ref<string | null>(null);
const opNotice = ref<string | null>(null);
const pendingKey = ref<string | null>(null);
const uninstallTarget = ref<PluginInventoryEntry | null>(null);
const uninstalling = ref(false);

const canImport = computed(() => pendingFile.value !== null && check.value === 'passed');

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
    error.value = apiErrorMessage(e, null);
  }
}

function onPick(file: File): void {
  pendingFile.value = file;
  check.value = 'checking';
  findings.value = [];
  opError.value = null;
  opNotice.value = null;
  void autoValidate(file);
}

function onClear(): void {
  pendingFile.value = null;
  check.value = 'idle';
  findings.value = [];
}

/** 选包即校验：通过后导入按钮才可用，操作者无需理解校验/导入的先后契约。 */
async function autoValidate(file: File): Promise<void> {
  try {
    const report = await validatePackage(file);
    check.value = report.valid ? 'passed' : 'failed';
    findings.value = report.findings;
  } catch (e) {
    check.value = 'failed';
    findings.value = [apiErrorMessage(e, '校验失败，请稍后重试') ?? '校验失败'];
  }
}

async function submitImport(): Promise<void> {
  if (!canImport.value || !pendingFile.value || importing.value) {
    return;
  }
  importing.value = true;
  pendingKey.value = 'import';
  opError.value = null;
  opNotice.value = null;
  try {
    const preview = await importPackage(pendingFile.value);
    opNotice.value = preview.isNew
      ? `已导入 ${preview.pluginId} ${preview.version}`
      : `该版本此前已导入，已直接引用 ${preview.pluginId} ${preview.version}`;
    onClear();
    await load();
  } catch (e) {
    opError.value = apiErrorMessage(e, '导入失败，请稍后重试');
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
    opError.value = apiErrorMessage(e, '操作失败，请稍后重试');
  } finally {
    pendingKey.value = null;
  }
}

function activate(plugin: PluginInventoryEntry, versionId: string, version: string): void {
  void run(
    `activate:${versionId}`,
    () => activateVersion(versionId),
    `已激活 ${plugin.name} ${version}`,
  );
}

function stop(plugin: PluginInventoryEntry): void {
  const activation = plugin.activations.find((item) => item.status === 'ACTIVE');
  if (!activation) {
    return;
  }
  void run(`stop:${activation.id}`, () => stopActivation(activation.id), `已停用 ${plugin.name}`);
}

async function submitUninstall(): Promise<void> {
  const plugin = uninstallTarget.value;
  if (uninstalling.value || !plugin) {
    return;
  }
  uninstalling.value = true;
  try {
    await run(
      `uninstall:${plugin.pluginId}`,
      () => uninstallPlugin(plugin.pluginId),
      `已卸载 ${plugin.name}`,
    );
    uninstallTarget.value = null;
  } finally {
    uninstalling.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="plugins-view" data-testid="plugins-view">
    <header class="plugins-header">
      <h2>{{ t('plugins.title', '插件管理') }}</h2>
      <BaseButton @click="load">{{ t('common.refresh', '刷新') }}</BaseButton>
    </header>

    <UploadDropzone
      :file="pendingFile"
      :check="check"
      :findings="findings"
      :importing="importing"
      @pick="onPick"
      @clear="onClear"
      @import="submitImport"
    />
    <p v-if="opNotice" class="op-notice" role="status">{{ opNotice }}</p>
    <p v-if="opError" class="form-error" role="alert">{{ opError }}</p>

    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">尚无插件，请在上方导入插件包</p>
    </StateView>
    <ul v-else class="plugin-list">
      <li v-for="plugin in plugins" :key="plugin.pluginId">
        <PluginCard
          :plugin="plugin"
          :pending="pendingKey !== null"
          :has-active="plugin.activations.some((item) => item.status === 'ACTIVE')"
          @activate="(versionId, version) => activate(plugin, versionId, version)"
          @stop="stop(plugin)"
          @uninstall="uninstallTarget = plugin"
        />
      </li>
    </ul>

    <ConfirmDialog
      :open="uninstallTarget !== null"
      title="卸载插件"
      :message="`将卸载 ${uninstallTarget?.name ?? ''}，其注册与实体将一并撤销，审计记录保留。`"
      confirm-label="卸载"
      danger
      :busy="uninstalling"
      @confirm="submitUninstall"
      @cancel="uninstallTarget = null"
    />
  </section>
</template>

<style scoped>
.plugins-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
