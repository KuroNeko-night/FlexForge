/**
 * 通用 keyed registry 基座（QG-4 单一实现路径）：extension.navigation /
 * field-renderer / record-action / layout / theme-asset 五个扩展点共用的
 * 注册-撤销语义（docs/extension-points.md §1）：
 * - 键唯一，后注册覆盖先注册（插件重激活场景）；
 * - close() 幂等且防陈旧（仅当当前值仍是自己时才移除）；
 * - 按 activationId 批量撤销（插件停用清理，不残留）。
 * 纯数据结构，不感知 Vue；各具体 registry 以薄封装复用。
 */
export interface Registration {
  close(): void;
}

interface Entry<T> {
  value: T;
  activationId: string | null;
  seq: number;
}

export class KeyedRegistry<T> {
  private readonly entries = new Map<string, Entry<T>>();
  private seq = 0;

  register(key: string, value: T, activationId: string | null = null): Registration {
    const entry: Entry<T> = { value, activationId, seq: ++this.seq };
    this.entries.set(key, entry);
    return {
      close: () => {
        if (this.entries.get(key) === entry) {
          this.entries.delete(key);
        }
      },
    };
  }

  /** 撤销指定键（无论归属；用于显式撤销路径）。 */
  revoke(key: string): void {
    this.entries.delete(key);
  }

  /** 按激活批次撤销全部注册项（插件停用：一次清理，不残留）。 */
  revokeByActivation(activationId: string): number {
    let removed = 0;
    for (const [key, entry] of this.entries) {
      if (entry.activationId === activationId) {
        this.entries.delete(key);
        removed++;
      }
    }
    return removed;
  }

  resolve(key: string): T | undefined {
    return this.entries.get(key)?.value;
  }

  /** 注册序快照（稳定迭代，菜单排序由具体 registry 决定）。 */
  list(): Array<{ key: string; value: T; activationId: string | null }> {
    return [...this.entries.entries()]
      .sort((a, b) => a[1].seq - b[1].seq)
      .map(([key, entry]) => ({ key, value: entry.value, activationId: entry.activationId }));
  }

  clear(): void {
    this.entries.clear();
  }
}
