<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import {
  activateVersion,
  fetchPluginInventory,
  importPackage,
  stopActivation,
  uninstallPlugin,
  upgradeVersion,
  validatePackage,
  type PluginInventoryEntry,
} from '@/api/plugins';
import PluginCard from '@/components/PluginCard.vue';
import PluginPresetBar from '@/components/PluginPresetBar.vue';
import StateView from '@/components/StateView.vue';
import BaseButton from '@/components/ui/BaseButton.vue';
import ConfirmDialog from '@/components/ui/ConfirmDialog.vue';
import UploadDropzone from '@/components/UploadDropzone.vue';
import { pluginConfirmSpec, type PluginConfirmTarget } from '@/utils/pluginConfirm';
import { usePluginPresets } from '@/composables/usePluginPresets';
import { t } from '@/registry/localeRegistry';

/**
 * 插件管理页（P21 操作逻辑重构）：inventory 视图 + 上传区（选包自动校验，通过才
 * 可导入）+ 卡片开关启停/版本切换/卸载（FR-PLUGIN-01/02 消费面，ADMIN；服务端
 * @RequireRole 为边界）+ 插件预设条（FR-PLUGIN-12）。卡片渲染在 PluginCard，
 * 预设状态与结果在 usePluginPresets，上传区交互在 UploadDropzone。
 */
const plugins = ref<PluginInventoryEntry[]>([]);
const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
const error = ref<string | null>(null);

const pendingFile = ref<File | null>(null);
const check = ref<'idle' | 'checking' | 'passed' | 'failed'>('idle');
const findings = ref<string[]>([]);
// 选包序号：陈旧守卫用（File 引用会被响应式代理包装，引用比较不可靠）
let pickSeq = 0;
const importing = ref(false);
const opError = ref<string | null>(null);
const opNotice = ref<string | null>(null);
const pendingKey = ref<string | null>(null);

const presetOps = usePluginPresets(refresh);

/** 统一确认对话目标（规格映射在 utils/pluginConfirm，P26 拆出）。 */
type ConfirmTarget = PluginConfirmTarget;
const confirmTarget = ref<ConfirmTarget | null>(null);

const confirmSpec = computed(() => pluginConfirmSpec(confirmTarget.value));

const canImport = computed(() => pendingFile.value !== null && check.value === 'passed');

/** P26 界面净化（FR-PLUGIN-15）：已停用插件默认折叠，开关仅会话内记忆。 */
const showInactive = ref(false);
const inactivePlugins = computed(() =>
  plugins.value.filter((p) => !p.activations.some((a) => a.status === 'ACTIVE')),
);
const visiblePlugins = computed(() =>
  showInactive.value
    ? plugins.value
    : plugins.value.filter((p) => p.activations.some((a) => a.status === 'ACTIVE')),
);

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

/** 预设操作后的清单联动刷新（403 已在首次 load 处理，这里静默容错）。 */
async function refresh(): Promise<void> {
  try {
    plugins.value = await fetchPluginInventory();
  } catch {
    /* 刷新失败不打断预设结果呈现，下次主刷新兜底 */
  }
}

function onPick(file: File): void {
  const seq = ++pickSeq;
  pendingFile.value = file;
  check.value = 'checking';
  findings.value = [];
  opError.value = null;
  opNotice.value = null;
  void autoValidate(file, seq);
}

function onClear(): void {
  pickSeq += 1;
  pendingFile.value = null;
  check.value = 'idle';
  findings.value = [];
}

/** 选包即校验：通过后导入按钮才可用，操作者无需理解校验/导入的先后契约。 */
async function autoValidate(file: File, seq: number): Promise<void> {
  try {
    const report = await validatePackage(file);
    // 陈旧守卫（审查 P2-6）：快速连选/清除时，慢响应不得覆盖新状态
    if (seq !== pickSeq) {
      return;
    }
    check.value = report.valid ? 'passed' : 'failed';
    findings.value = report.findings;
  } catch (e) {
    if (seq !== pickSeq) {
      return;
    }
    check.value = 'failed';
    findings.value = [
      apiErrorMessage(e, t('plugins.validateFailed', '校验失败，请稍后重试')) ??
        t('plugins.validateFailedShort', '校验失败'),
    ];
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
      ? `${t('plugins.imported', '已导入')} ${preview.pluginId} ${preview.version}`
      : `${t('plugins.reused', '该版本此前已导入，已直接引用')} ${preview.pluginId} ${preview.version}`;
    onClear();
    await load();
  } catch (e) {
    opError.value = apiErrorMessage(e, t('plugins.importFailed', '导入失败，请稍后重试'));
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
    opError.value = apiErrorMessage(e, t('common.opFailed', '操作失败，请稍后重试'));
  } finally {
    pendingKey.value = null;
  }
}

/** 开关启停（P21）：开=激活当前版本（未启用时=最新导入），关=停用当前激活。 */
function onToggle(plugin: PluginInventoryEntry, next: boolean): void {
  if (next) {
    const version = plugin.versions.at(-1);
    if (!version) {
      return;
    }
    void run(
      `activate:${version.versionId}`,
      () => activateVersion(version.versionId),
      `${t('plugins.enabled', '已启用')} ${plugin.name} ${version.version}`,
    );
    return;
  }
  const activation = plugin.activations.find((item) => item.status === 'ACTIVE');
  if (activation) {
    void run(
      `stop:${activation.id}`,
      () => stopActivation(activation.id),
      `${t('plugins.disabled', '已停用')} ${plugin.name}`,
    );
  }
}

/** 版本切换：启用中经 upgrade（自动停旧+失败补偿），未启用直接激活。
 * 动作须惰性求值——急切创建会在 run 单飞拒绝时留下无人消费的已发请求（审查 P2-1）。 */
function onSwitchVersion(plugin: PluginInventoryEntry, versionId: string, version: string): void {
  const active = plugin.activations.find((item) => item.status === 'ACTIVE');
  const action = () => (active ? upgradeVersion(versionId) : activateVersion(versionId));
  void run(
    `switch:${versionId}`,
    action,
    `${t('plugins.switched', '已切换')} ${plugin.name} → ${version}`,
  );
}

async function submitConfirm(): Promise<void> {
  const target = confirmTarget.value;
  if (!target) {
    return;
  }
  confirmTarget.value = null;
  if (target.kind === 'uninstall') {
    await run(
      `uninstall:${target.plugin.pluginId}`,
      () => uninstallPlugin(target.plugin.pluginId),
      `${t('plugins.uninstalled', '已卸载')} ${target.plugin.name}`,
    );
  } else if (target.kind === 'apply') {
    presetOps.apply(target.preset);
  } else {
    presetOps.remove(target.preset);
  }
}

onMounted(() => {
  void load();
  void presetOps.load().catch(() => {
    /* 预设清单加载失败不毁主列表，操作时按需报错 */
  });
});
</script>

<template>
  <section class="plugins-view" data-testid="plugins-view">
    <header class="plugins-header">
      <h2 class="ff-page-title">{{ t('plugins.title', '插件管理') }}</h2>
      <div class="plugins-header-actions">
        <BaseButton
          v-if="inactivePlugins.length > 0"
          data-testid="toggle-inactive"
          @click="showInactive = !showInactive"
        >
          {{
            showInactive
              ? t('plugins.hideInactive', '收起已停用')
              : `${t('plugins.showInactive', '显示已停用')}（${inactivePlugins.length}）`
          }}
        </BaseButton>
        <BaseButton @click="load">{{ t('common.refresh', '刷新') }}</BaseButton>
      </div>
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

    <PluginPresetBar
      :presets="presetOps.presets.value"
      :busy="presetOps.busy.value"
      :failures="presetOps.failures.value"
      @save="presetOps.save"
      @apply="confirmTarget = { kind: 'apply', preset: $event }"
      @remove="confirmTarget = { kind: 'remove', preset: $event }"
    />
    <p v-if="presetOps.notice.value" class="op-notice" role="status">
      {{ presetOps.notice.value }}
    </p>
    <p v-if="presetOps.error.value" class="form-error" role="alert">
      {{ presetOps.error.value }}
    </p>
    <StateView v-if="state !== 'ready'" :state="state" :message="error">
      <p v-if="state === 'empty'">{{ t('plugins.empty', '尚无插件，请在上方导入插件包') }}</p>
    </StateView>
    <p v-else-if="visiblePlugins.length === 0" class="op-notice" data-testid="inactive-only-hint">
      {{ t('plugins.allInactiveHint', '当前仅停用插件，点击"显示已停用"查看') }}
    </p>
    <ul v-else class="plugin-list">
      <li v-for="plugin in visiblePlugins" :key="plugin.pluginId">
        <PluginCard
          :plugin="plugin"
          :pending="pendingKey !== null || presetOps.busy.value"
          @toggle="(next) => onToggle(plugin, next)"
          @switch-version="(versionId, version) => onSwitchVersion(plugin, versionId, version)"
          @uninstall="confirmTarget = { kind: 'uninstall', plugin }"
        />
      </li>
    </ul>

    <ConfirmDialog
      :open="confirmTarget !== null"
      :title="confirmSpec.title"
      :message="confirmSpec.message"
      :confirm-label="confirmSpec.confirmLabel"
      :danger="confirmSpec.danger"
      :busy="pendingKey !== null || presetOps.busy.value"
      @confirm="submitConfirm"
      @cancel="confirmTarget = null"
    />
  </section>
</template>

<style scoped>
.plugins-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.plugins-header-actions {
  display: flex;
  gap: var(--ff-space-2);
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
