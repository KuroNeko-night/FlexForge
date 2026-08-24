# FlexForge 扩展点登记册

**版本**：v1.0
**状态**：active（MVP 基线）
**日期**：2026-08-20
**适用范围**：平台所有对外能力点（ServiceKey、ExtensionPoint、DomainEvent）与受控契约的登记和演化

> 本文是扩展点的唯一事实来源。运行时注册表（`ServiceRegistry` / `ExtensionRegistry`）保存"某次激活注册了什么"；本文保存"平台有哪些合法扩展点、契约是什么、谁负责演化"。两者不可互相替代。

## 1. 登记规则

1. **先登记后使用**：任何模块在公开或消费一个新扩展点之前，必须先在本文件登记为 `proposed`；实现完成并通过"注册/撤销"测试后转为 `active`。代码评审发现未登记扩展点视为不合格。
2. **必须有消费方**：`active` 扩展点必须至少有一个真实消费方（内置模块或 Level 1 插件贡献）。没有消费方的抽象不进入 MVP。P02 出口口径：消费方落地前的 active 项（按"引入阶段"列排期）以注册-撤销测试与 R-GOV-03 门禁作为守护消费方，生产消费方在计划阶段落地。
3. **必须可撤销**：每个运行时注册项绑定 `activationId`，停用/卸载时通过 disposer 全部撤销（对应 `FR-PLUGIN-06`、`NFR-PLUGIN-01`）。
4. **兼容性分类**：
   - `additive`：新增扩展点、新增可选字段、新增错误码——直接登记并更新本表。
   - `breaking`：改名、删字段、改语义——必须先过 ADR，并在同一 PR 完成全部消费方迁移，禁止"先破后修"。
5. **废弃流程**：先标记 `deprecated` 并给出替代 ID，保留至少一个里程碑；确认无消费方后才可删除行。
6. **行契约**：每行必须包含 `id`、`kind`、`payload 契约`、`owner 模块`、`MVP 消费方`、`引入阶段`、`状态`。

## 2. MVP v1 登记清单

### 2.1 ServiceKey

| ID | 契约/载荷 | 责任模块 | MVP 消费方 | 引入阶段 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `service.meta` | `MetaRegistry`：实体/字段/视图查询与缓存失效 | flexforge-meta | flexforge-data、flexforge-plugin、前端元数据 API | P02 定义，P04 落地 | active |
| `service.data-access` | 受控动态数据读写：白名单字段映射、参数化查询、排序分页 | flexforge-data（DynamicRecordService + 单 JSONB data_record 存储，P05 落地） | 动态 CRUD API、`extension.record-action` 内置动作 | P02 定义，P05 已落地 | active |
| `service.audit` | 审计事件写入端口：`AuditEvent{id, actor, action, objectId, result, occurredAt}` | flexforge-system | 全部写路径模块 | P02 定义，P03 落地 | active |

### 2.2 ExtensionPoint

| ID | 贡献载荷 | 责任模块 | MVP 消费方 | 引入阶段 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `extension.navigation` | `{key, title, route, icon, order, permissionKey}` | flexforge-plugin（注册表） | 后端菜单 API、前端菜单 registry | P03 准备，P08 插件注册 | active |
| `extension.field-renderer` | `{fieldType, rendererId}`；`rendererId` 必须是平台内置 ID——P04 起由 `FieldTypeRegistry`（flexforge-meta）落定并校验，内置集 = 六类默认 ID：`text.default` / `integer.default` / `decimal.default` / `date.default` / `enum.default` / `boolean.default` | flexforge-meta（类型契约）+ 前端 renderer registry | 前端动态列表/表单/详情（P04 配置 API 已按此校验） | P04 准备，P06 落地 | active |
| `extension.record-action` | `{actionType, handlerId, label, permissionKey}`；`handlerId` 必须是平台内置 ID | flexforge-plugin | 前端动态表格动作栏 | P05 准备，P09 示例落地 | active |
| `extension.layout` | `{key, target, slots: [{name, items: [{key, order}]}]}`；target 为平台登记的页面/容器 key，items 引用部件贡献 key（2026-08-24 需求澄清 FR-PLUGIN-10） | flexforge-plugin（注册表）+ 前端 layout registry | 动态页面/工作台容器 | P06 准备，P07/P08 插件注册 | proposed |
| `extension.theme-asset` | `{key, kind: background\|icon\|animation, path, scope?}`；path 指向插件包内经 S6 校验的静态资源，scope 缺省全局（FR-PLUGIN-11） | flexforge-plugin（注册表）+ 前端 theme registry | 动态页面外观、菜单图标 | P06 准备，P07/P08 插件注册 | proposed |

### 2.3 DomainEvent

| ID | 载荷 | 责任模块 | MVP 消费方 | 引入阶段 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `event.domain` | `DomainEvent{eventId, type, aggregateId, occurredAt, payload, activationId?}` | flexforge-runtime | flexforge-system 审计监听、Issue 状态流转审计；为 P13 可选轻量工作流预留监听点 | P02 定义 | active |

### 2.4 受控契约（非运行时注册，但属于同一兼容面）

| 契约 | 当前版本 | 演化规则 |
| --- | --- | --- |
| `plugin.json.schemaVersion` | 1 | 平台只接受已登记版本；MVP 仅实现 v1 reader，未知版本返回 `unsupported_schema_version`；新增可选字段为 additive；破坏性变更必须新 schemaVersion 并保留旧 reader 至少一个小版本周期 |
| `RequirementSpec.schemaVersion` | 1 | 与 Issue 记录绑定、可审计；additive 加可选字段；breaking 新版本 + 迁移说明 |
| REST API 前缀 | `/api/v1/` | 破坏性 API 变更提升主版本；客户端必须容忍未知错误码 |
| 稳定错误码集合 | 基线见 `docs/08-implementation-blueprint.md` §7 | 只增、不改名、不删；删除或改名是 breaking，需要 ADR |
| 插件 `contributions` 键 | `navigation` → `extension.navigation`；`renderers` → `extension.field-renderer` | 新键必须先登记为 ExtensionPoint，否则 manifest 校验拒绝 |

## 3. 新增扩展点提案模板

```text
ID: <建议 ID，采用反向命名，如 extension.xxx / service.xxx / event.xxx>
Kind: ServiceKey | ExtensionPoint | DomainEvent
Owner: <责任模块>
Payload: <契约字段与类型>
MVP 消费方: <至少一个真实消费方>
引入阶段: <P 编号>
验证: <注册/撤销测试与失败路径>
状态: proposed
```

提交方式：与实现同一 PR；评审通过后由维护者将状态改为 `active`。

## 4. 变更记录

| 日期 | 变更 | 类型 |
| --- | --- | --- |
| 2026-08-20 | 建立 MVP v1 登记清单；统一 03/08 文档中的扩展点 ID | additive |
| 2026-08-24 | P02 出口：v1 清单落地为代码常量（flexforge-common registry 包）与运行时注册表（flexforge-runtime）；§1.2 补充消费方落地前口径；R-GOV-03 门禁强制常量 == active 集合 | additive |
| 2026-08-24 | GUI 定制需求澄清（用户）：插件驱动部件/布局/美术资产，非拖拽设计器；新增 `extension.layout` 与 `extension.theme-asset` 两条 proposed（FR-PLUGIN-09/10/11），实现 PR 落地时转 active | additive |
| 2026-08-24 | `extension.field-renderer` 内置 renderer ID 集落定为六类默认 ID（`<type>.default`，FieldTypeRegistry 校验 + P06 前端按 ID 实现组件） | additive |
| 2026-08-24 | `service.data-access` P05 落地：flexforge-data 为责任模块（data_record 单 JSONB 记录表，docs/03 §4 存储定案） | additive |
