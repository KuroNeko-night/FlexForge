/**
 * 视图可见列解析（P19 抽出，QG-4 单一实现路径）：DynamicTable 列与 CSV 导出列
 * 共用同一规则——view.columns（visible 序）映射字段，未声明/为空时缺省=全部
 * 字段按 position 序。KanbanView 卡片列缺省不同（前三个字段），不复用本函数。
 */
export function visibleColumns<T extends { name: string; position: number }>(
  fields: T[],
  view: { columns?: { field: string; visible?: boolean }[] | null } | null,
): T[] {
  const ordered = [...fields].sort((a, b) => a.position - b.position);
  const viewColumns = view?.columns?.filter((column) => column.visible !== false) ?? null;
  if (!viewColumns || viewColumns.length === 0) {
    return ordered;
  }
  const byName = new Map(ordered.map((field) => [field.name, field]));
  return viewColumns
    .map((column) => byName.get(column.field))
    .filter((field): field is T => field !== undefined);
}
