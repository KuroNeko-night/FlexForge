import { computed, ref } from 'vue';

import { KeyedRegistry } from '@/registry/keyed';

/**
 * 美术资产 registry（extension.theme-asset 前端消费面，FR-PLUGIN-11）：
 * 背景图/图标/动画贡献以 CSS 变量注入（--ff-theme-background 等），平台默认外观
 * = 不设置变量（CSS 缺省兜底）；注册/撤销即时生效（响应式版本号驱动），
 * 停用/卸载后恢复默认。path 指向插件包内经 S6 校验的静态资源（P07/P08 下发），
 * 前端只消费声明值，不做任何脚本执行（S5）。
 */
export type ThemeAssetKind = 'background' | 'icon' | 'animation';

export interface ThemeAssetContribution {
  key: string;
  kind: ThemeAssetKind;
  path: string;
  /** 作用域（如页面/容器 key）；缺省全局。 */
  scope?: string | null;
}

export const THEME_CSS_VARIABLES: Record<ThemeAssetKind, string> = {
  background: '--ff-theme-background',
  icon: '--ff-theme-icon',
  animation: '--ff-theme-animation',
};

const registry = new KeyedRegistry<ThemeAssetContribution>();
const version = ref(0);

function bump(): void {
  version.value += 1;
}

export function registerThemeAsset(
  contribution: ThemeAssetContribution,
  activationId: string | null = null,
) {
  validatePath(contribution.path);
  const registration = registry.register(contribution.key, contribution, activationId);
  bump();
  return {
    close: () => {
      registration.close();
      bump();
    },
  };
}

/**
 * path 最小防御（P07 S6 包校验的纵深防线）：非空、无首尾空白、相对路径
 * （拒绝任意 scheme 前缀与协议相对 // 开头，防外链请求的信息泄露面）。
 */
function validatePath(path: string): void {
  const trimmed = path.trim();
  const isExternal =
    trimmed === '' ||
    trimmed !== path ||
    trimmed.startsWith('//') ||
    /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed);
  if (typeof path !== 'string' || isExternal) {
    throw new Error(`theme asset path 必须是非空相对路径（拒绝外链）: ${String(path)}`);
  }
}

export function revokeThemeAsset(key: string): void {
  registry.revoke(key);
  bump();
}

export function revokeThemeAssetsByActivation(activationId: string): number {
  const removed = registry.revokeByActivation(activationId);
  if (removed > 0) {
    bump();
  }
  return removed;
}

/**
 * 解析某作用域生效的资产（同 kind+scope 后注册者胜；特定 scope 覆盖全局）。
 * 依赖响应式版本号：注册/撤销后 computed 自动重算（即时生效/恢复默认）。
 */
export function resolveThemeAsset(kind: ThemeAssetKind, scope?: string | null) {
  return computed<ThemeAssetContribution | null>(() => {
    void version.value;
    let globalAsset: ThemeAssetContribution | null = null;
    let scopedAsset: ThemeAssetContribution | null = null;
    for (const { value } of registry.list()) {
      if (value.kind !== kind) {
        continue;
      }
      if (scope && value.scope === scope) {
        scopedAsset = value;
      } else if (!value.scope) {
        globalAsset = value;
      }
    }
    return scopedAsset ?? globalAsset;
  });
}

/** 组装 CSS 变量样式对象（App/壳层 :style 绑定；无资产时为空对象=平台默认外观）。 */
export function themeStyle(scope?: string | null) {
  const background = resolveThemeAsset('background', scope);
  const icon = resolveThemeAsset('icon', scope);
  const animation = resolveThemeAsset('animation', scope);
  return computed<Record<string, string>>(() => {
    const style: Record<string, string> = {};
    for (const [asset, kind] of [
      [background.value, 'background'],
      [icon.value, 'icon'],
      [animation.value, 'animation'],
    ] as const) {
      if (asset) {
        // background 需要 <url>() 语法（裸路径是非法值，整条属性会失效）；
        // icon/animation 由消费方自行组装（P09 示例落地），此处只透传路径
        style[THEME_CSS_VARIABLES[kind]] =
          kind === 'background'
            ? `url("${asset.path.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`
            : asset.path;
      }
    }
    return style;
  });
}
