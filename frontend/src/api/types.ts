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
  /** 插件实体字段注册不带 rendererId（null）——前端按 fieldType 回退默认渲染器。 */
  rendererId: string | null;
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
  /** P17 扩 kanban：groupBy 必填（分列 enum 字段，后端 ViewRules 校验）。 */
  viewType: 'list' | 'form' | 'kanban';
  name: string;
  columns: ViewColumn[] | null;
  filters: ViewFilter[] | null;
  groupBy: string | null;
}

export interface EntitySummary {
  id: string;
  name: string;
  displayName: string;
  status: 'draft' | 'enabled' | 'disabled';
  pluginId: string | null;
  updatedAt: string;
}

export interface EntityDetail extends Omit<EntitySummary, 'updatedAt'> {
  fields: FieldDefinition[];
  views: ViewDefinition[];
  /** 元数据版本：递增即提示前端重新拉取（P04 契约）。 */
  metaVersion: number;
  /** 后端详情响应当前不含 updatedAt（列表接口才有）；镜像标记为可选。 */
  updatedAt?: string;
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
