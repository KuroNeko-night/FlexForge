/**
 * 后端元数据/数据契约的前端镜像（flexforge-meta / flexforge-data API 载荷）。
 * 类型只描述字段，不做任何运行时构造；渲染侧禁止把这些元数据当 HTML/脚本执行（S5）。
 */
export type FieldTypeWire = 'text' | 'integer' | 'decimal' | 'date' | 'enum' | 'boolean';

export interface FieldDefinition {
  id: string;
  name: string;
  displayName: string;
  fieldType: FieldTypeWire;
  required: boolean;
  defaultValue: unknown;
  validation: Record<string, unknown> | null;
  rendererId: string;
  position: number;
}

export interface ViewColumn {
  field: string;
  visible?: boolean;
}

export interface ViewFilter {
  field: string;
  operator: 'eq' | 'contains' | 'gte' | 'lte';
}

export interface ViewDefinition {
  id: string;
  viewType: 'list' | 'form';
  name: string;
  columns: ViewColumn[] | null;
  filters: ViewFilter[] | null;
}

export interface EntitySummary {
  id: string;
  name: string;
  displayName: string;
  status: 'draft' | 'enabled' | 'disabled';
  pluginId: string | null;
  updatedAt: string;
}

export interface EntityDetail extends EntitySummary {
  fields: FieldDefinition[];
  views: ViewDefinition[];
  /** 元数据版本：递增即提示前端重新拉取（P04 契约）。 */
  metaVersion: number;
}

export interface RecordView {
  id: string;
  entity: string;
  data: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface PageResult<T> {
  items: T[];
  total: number;
  pageNumber: number;
  pageSize: number;
}

export interface MenuItem {
  key: string;
  title: string;
  route: string | null;
  icon?: string | null;
  order?: number;
  permissionKey?: string | null;
}
