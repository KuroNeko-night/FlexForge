import { computed, ref } from 'vue';
import type { Component } from 'vue';

import { KeyedRegistry } from '@/registry/keyed';

/**
 * 布局 registry（extension.layout 前端消费面，FR-PLUGIN-10，2026-08-24 GUI 澄清）：
 * 布局贡献声明页面/容器 target 的槽位与部件编排；部件本身经 widget registry
 * 按贡献 key 解析（内置部件 + P08 插件部件）。无贡献或有未解析部件时按缺省
 * 布局兜底；注册/撤销即时生效（响应式版本号）。声明式数据只驱动组件选择与
 * 顺序，不执行任何脚本（S5）。
 */
export interface LayoutSlotItem {
  key: string;
  order: number;
}

export interface LayoutSlot {
  name: string;
  items: LayoutSlotItem[];
}

export interface LayoutContribution {
  key: string;
  /** 平台登记的页面/容器 key（如 workbench.main）。 */
  target: string;
  slots: LayoutSlot[];
}

export type ResolvedSlots = Array<{ name: string; widgetKeys: string[] }>;

const layouts = new KeyedRegistry<LayoutContribution>();
const widgets = new KeyedRegistry<Component>();
const version = ref(0);

function bump(): void {
  version.value += 1;
}

export function registerLayout(
  contribution: LayoutContribution,
  activationId: string | null = null,
) {
  const registration = layouts.register(contribution.key, contribution, activationId);
  bump();
  return {
    close: () => {
      registration.close();
      bump();
    },
  };
}

export function revokeLayout(key: string): void {
  layouts.revoke(key);
  bump();
}

export function revokeLayoutsByActivation(activationId: string): number {
  const removed = layouts.revokeByActivation(activationId);
  if (removed > 0) {
    bump();
  }
  return removed;
}

export function registerWidget(
  key: string,
  component: Component,
  activationId: string | null = null,
) {
  const registration = widgets.register(key, component, activationId);
  bump();
  return {
    close: () => {
      registration.close();
      bump();
    },
  };
}

/**
 * 解析某 target 的生效槽位：无任何布局贡献 → 调用方缺省布局兜底；
 * 有贡献 → 合并各贡献槽位（items 按 order→key 排序、同槽位 widget key 去重），
 * 未注册部件 key 跳过（容忍悬空引用）；空 slots 贡献即清空该 target 区域。
 */
export function resolveLayout(target: string, defaultSlots?: ResolvedSlots) {
  return computed<ResolvedSlots>(() => {
    void version.value;
    const contributions = layouts
      .list()
      .map((entry) => entry.value)
      .filter((layout) => layout.target === target);
    if (contributions.length === 0) {
      return defaultSlots ?? [];
    }
    const bySlot = new Map<string, LayoutSlotItem[]>();
    for (const contribution of contributions) {
      for (const slot of contribution.slots) {
        bySlot.set(slot.name, [...(bySlot.get(slot.name) ?? []), ...slot.items]);
      }
    }
    return [...bySlot.entries()].map(([name, items]) => ({
      name,
      widgetKeys: [
        ...new Set(
          items
            .sort((a, b) => a.order - b.order || a.key.localeCompare(b.key))
            .map((item) => item.key),
        ),
      ].filter((key) => widgets.resolve(key) !== undefined),
    }));
  });
}

export function resolveWidget(key: string): Component | undefined {
  return widgets.resolve(key);
}
