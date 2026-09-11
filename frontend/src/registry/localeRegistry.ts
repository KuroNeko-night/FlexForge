import { ref } from 'vue';

/**
 * 界面文案 registry（P15，FR-SETUP-02；extension.theme-asset kind=locale 前端
 * 消费面）：平台内置 zh-CN 基线（结构=平台基建），语言内容以 Level 1 插件分发
 * （皮）——locale 插件激活后其语言包出现在可选语言中，停用即回退基线。
 * 语言偏好存 localStorage（用户级设置），切换即时生效（响应式版本号）。
 */
export interface LocalePack {
  lang: string;
  messages: Record<string, string>;
}

const STORAGE_KEY = 'flexforge.locale';
const BASELINE_LANG = 'zh-CN';

const packs = ref<Record<string, LocalePack>>({});
const current = ref(readStoredLanguage());
const version = ref(0);

function readStoredLanguage(): string {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) || BASELINE_LANG;
  } catch {
    return BASELINE_LANG;
  }
}

/** 注册语言包（theme-assets 聚合 kind=locale，签名 URL 取回 JSON）。 */
export function registerLocalePack(activationId: string, pack: LocalePack): void {
  requireSafeMessages(pack.messages);
  packs.value = { ...packs.value, [activationId]: pack };
  version.value += 1;
}

/** 差量撤销：不在 activeIds 中的激活，其语言包一并移除（停用插件回退基线）。 */
export function syncRemovedLocalePacks(activeIds: string[]): void {
  const alive = new Set(activeIds);
  const next: Record<string, LocalePack> = {};
  let changed = false;
  for (const [activationId, pack] of Object.entries(packs.value)) {
    if (alive.has(activationId)) {
      next[activationId] = pack;
    } else {
      changed = true;
    }
  }
  if (changed) {
    packs.value = next;
    version.value += 1;
    if (!availableLanguages().includes(current.value)) {
      setLanguage(BASELINE_LANG);
    }
  }
}

/** 消息键白名单：界面文案 key（点分隔命名空间，段内允许驼峰/连字符/下划线），
 *  拒绝 CSS 变量（-- 前缀）、URL/scheme（冒号）等非文案形态注入面。 */
const MESSAGE_KEY_PATTERN = /^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$/;

function requireSafeMessages(messages: Record<string, string>): void {
  for (const [key, value] of Object.entries(messages)) {
    if (!MESSAGE_KEY_PATTERN.test(key) || typeof value !== 'string') {
      throw new Error(`locale 消息键/值非法（仅界面文案 key）: ${key}`);
    }
  }
}

/** 可选语言：平台基线 + 已激活语言包（按注册顺序追加；跨基线去重——
 * 声明 zh-CN 的包不产生第二个基线选项，其消息也永不生效=基线即权威）。 */
export function availableLanguages(): string[] {
  const langs = Object.values(packs.value).map((pack) => pack.lang);
  return Array.from(new Set([BASELINE_LANG, ...langs]));
}

export function currentLanguage(): string {
  return current.value;
}

export function setLanguage(lang: string): void {
  if (!availableLanguages().includes(lang)) {
    return;
  }
  current.value = lang;
  version.value += 1;
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, lang);
  } catch {
    /* 存储不可用时仅内存生效 */
  }
}

/**
 * 文案解析：当前语言包命中即覆盖，否则回退调用方给定的基线文案。
 * 基线即源码内中文字面量（结构），插件语言包是唯一覆盖来源（皮）。
 * 渲染期调用会订阅版本号，语言切换/语言包增删即时重渲染。
 */
export function t(key: string, fallback: string): string {
  void version.value;
  if (current.value !== BASELINE_LANG) {
    for (const pack of Object.values(packs.value)) {
      if (pack.lang === current.value && pack.messages[key] !== undefined) {
        return pack.messages[key];
      }
    }
  }
  return fallback;
}

/** 语言名展示（设置页选项标签）。 */
export const LANGUAGE_LABELS: Record<string, string> = {
  'zh-CN': '简体中文',
  en: 'English',
  ja: '日本語',
  fr: 'Français',
  es: 'Español',
};
