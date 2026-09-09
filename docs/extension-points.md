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
| `extension.record-action` | `{actionType, handlerId, label, permissionKey}`；`handlerId` 必须是平台内置 ID。前端映射（P06）：`actionType`→动作 key（动作栏按钮），`handlerId`→平台内置 handler 查找键（前端 record-action registry） | flexforge-plugin | 前端动态表格动作栏 | P05 准备，P09 示例落地 | active |
| `extension.layout` | `{key, target, slots: [{name, items: [{key, order}]}]}`；target 为平台登记的页面/容器 key，items 引用部件贡献 key（2026-08-24 需求澄清 FR-PLUGIN-10） | flexforge-plugin（注册表）+ 前端 layout registry（P06 消费面落地） | 动态页面/工作台容器 | P06 消费面落地，P07/P08 插件注册 | active |
| `extension.data-processor` | `{key, label, kind: 'python', entry, inputEntity}`；entry 指向包内 `scripts/*.py`（S6 Level 2 校验），inputEntity 为目标实体名。执行契约：stdin `{records:[{...data}]}` → stdout `{kind:'table', columns:[{name,label}], rows:[[...]]}`、`{kind:'summary', items:[{label,value}]}` 或 `{kind:'chart', chartType:'bar'\|'pie', title, categories:[..], values:[..]}`（平台 Schema 校验；chart：categories ≤50 项每项 ≤100 字符、values 有限数字等长 ≤50、pie 值 ≥0，P22）；P23 起可选 `inputMode:'file'` 变体（缺省 entity，此时 inputEntity 省略）：声明 `accept`（扩展名白名单 ⊆ csv/xlsx/txt）+ `maxInputMB`（≤5），经 `POST .../invoke-file`（multipart）执行——脚本以 argv[1]=平台分配的输入文件路径、env `FLEXFORGE_OUTPUT_DIR`=输出目录运行，stdout 额外允许 `{kind:'file', filename}`（≤200 字符安全字符集；产物必须已在输出目录内且 ≤10MB，违约 `processor_output_invalid`），产物登记 `processor_artifact` 归属创建者、TTL 10 分钟、下载走归属校验端点（FR-PLUGIN-14、docs/13 §3.5-5）。子进程 `python3 -I`+超时+IO/行数上限=平台单页 200 行（ADR-0002 Level 2 受信边界）。invoke/invoke-file 与动态数据读同权（ADMIN/USER） | flexforge-plugin（注册表+执行引擎）+ 前端实体页分析抽屉与 /tools 文件工具页 | plugins/example-analytics（月度透视/合格率/负载汇总，P20）；plugins/example-filetools（CSV 清洗/画像，P23） | P20（file 变体 P23） | active |
| `extension.theme-asset` | `{key, kind: background\|icon\|animation\|tokens, path, scope?}`；path 指向插件包内经 S6 校验的静态资源，scope 缺省全局（FR-PLUGIN-11）。P12.5 起 kind=tokens：path 为 assets/ 内 JSON 键值表，键须匹配 `^--ff-[a-z0-9-]+$`（前端白名单，仅平台设计令牌可覆盖；多 tokens 并存后应用者胜，撤销恢复基线）；P15 起 kind=locale：path 为 assets/ 内 JSON 文档 `{lang, messages}`（界面文案语言包，键=点分文案 key，前端 localeRegistry 白名单 `^[a-z][a-zA-Z0-9]*(\.[a-zA-Z0-9_-]+)+$`（段内允许驼峰/连字符/下划线；`--` 前缀与冒号拒绝）；设置页语言切换消费，插件停用回退平台中文基线，FR-SETUP-02） | flexforge-plugin（注册表+聚合端点 GET /plugins/theme-assets）+ 前端 theme registry（壳层 CSS 变量注入） | 动态页面外观、菜单图标、主题换肤（plugins/theme-default、theme-warm） | P06 消费面落地，P07/P08 插件注册，P12.5 tokens 闭环 | active |

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

### 2.3 视图类型契约（metadata/views 资源，随 `service.meta`）

| viewType | 必填配置 | 语义 | 引入 |
| --- | --- | --- | --- |
| `list` | — | 表格列与查询字段（columns/filters，ViewRules 白名单） | P04 |
| `form` | — | 表单字段顺序 | P04 |
| `kanban` | `groupBy`（必须是同实体 enum 字段，ViewRules.validateKanban） | 看板列=groupBy 枚举选项（含前端"未设置"兜底列），columns=卡片显示字段；第一版只读，移动记录=详情内编辑分组字段（P17，插件声明即得，无脚本语义） | P17 |

## 4. 变更记录

| 日期 | 变更 | 类型 |
| --- | --- | --- |
| 2026-08-20 | 建立 MVP v1 登记清单；统一 03/08 文档中的扩展点 ID | additive |
| 2026-08-24 | P02 出口：v1 清单落地为代码常量（flexforge-common registry 包）与运行时注册表（flexforge-runtime）；§1.2 补充消费方落地前口径；R-GOV-03 门禁强制常量 == active 集合 | additive |
| 2026-08-24 | GUI 定制需求澄清（用户）：插件驱动部件/布局/美术资产，非拖拽设计器；新增 `extension.layout` 与 `extension.theme-asset` 两条 proposed（FR-PLUGIN-09/10/11），实现 PR 落地时转 active | additive |
| 2026-08-24 | `extension.field-renderer` 内置 renderer ID 集落定为六类默认 ID（`<type>.default`，FieldTypeRegistry 校验 + P06 前端按 ID 实现组件） | additive |
| 2026-08-24 | `service.data-access` P05 落地：flexforge-data 为责任模块（data_record 单 JSONB 记录表，docs/03 §4 存储定案） | additive |
| 2026-08-25 | `extension.layout` 与 `extension.theme-asset` 由 proposed 转 active：前端消费面（layout/theme registry：槽位编排渲染、CSS 变量注入、缺省兜底与撤销恢复）随 P06 迭代 2 落地；插件侧注册 P07/P08 接入；常量同步 flexforge-common ExtensionPoints（R-GOV-03 对齐） | additive |
| 2026-08-29 | P08 插件侧注册落地：plugin.json `contributions.themeAssets`（对象数组）→ 导入校验+逐文件声明收紧（assets/ 双向核对，Issue #20 第 3 项）→ 激活注册（plugin_registration + 内存 ThemeAssetContribution）→ serve 端点（CSP/attachment/nosniff，Issue #20 第 4 项）；载荷契约不变 | additive |
| 2026-08-30 | P12.5 tokens 通道（additive）：`extension.theme-asset` kind 枚举扩 `tokens`（JSON 键值表→--ff-* 设计令牌，前端键白名单）；新增聚合端点 GET /plugins/theme-assets（登录可读，前端壳层消费面）；消费方 plugins/theme-default、theme-warm | additive |
| 2026-08-30 | P15 locale 通道（additive）：`extension.theme-asset` kind 枚举扩 `locale`（JSON 语言包 `{lang,messages}`→前端 localeRegistry+t()/设置页语言切换；差量撤销回退中文基线）；消费方 plugins/locale-en | additive |（同日修正：键白名单段内放宽驼峰——live 排障发现全小写正则静默拒绝真实语言包，PR #37）
| 2026-09-01 | P17 看板视图（additive）：视图类型契约（§2.3）扩 `kanban`——`groupBy` 必填且为同实体 enum 字段；V013 迁移扩 meta_view.view_type CHECK+group_by 列；插件 spec/元数据 API/前端 KanbanView 全链同一 ViewRules 校验；消费方 plugins/example-kanban | additive |
| 2026-09-04 | P20 数据处理器（additive + ADR-0002 Level 2 激活）：新增 `extension.data-processor`（插件声明 Python 数据处理器，子进程受控执行+输出 Schema 校验，S6 修订为双轨校验）；消费方 plugins/example-analytics | additive（含 ADR 修订） |
| 2026-09-08 | P22 处理器图表输出（additive）：`extension.data-processor` stdout 契约新增 kind=chart（bar/pie，定长 categories/values 数值数组），消费方=平台 ChartCanvas 图表基建（FR-CHART-01）；example-analytics 0.2.0 增双图处理器 | additive（既有 table/summary 与错误码不动） |
| 2026-09-09 | P23 文件输入处理器（additive + S6/ADR-0002 Level 2 边界表增补文件 IO 面）：`extension.data-processor` 声明新增可选 `inputMode:'file'`（缺省 entity 不变）+ `accept`/`maxInputMB`；stdout 契约新增 kind=file（filename 安全字符集+产物目录边界+10MB 上限）；新端点 invoke-file/artifact 下载（FR-PLUGIN-14，docs/13 §3.5-5）；消费方=平台 /tools 文件工具页 + plugins/example-filetools | additive（既有 entity 输入/table/summary/chart 契约与错误码不动） |
