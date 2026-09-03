# FlexForge 详细实施阶段计划

**计划基线**：2026-08-20
**阶段编号**：`P00` - `P14`
**阶段规则**：前一阶段未达到退出条件，不进入后一阶段；加分项不得阻塞主线。
**周期口径**：总览表"建议周期"单位为投入天（8 小时/天），按每周 20-25 小时投入折算约 2.5-3 天/周；各阶段的日历目标日期以 `docs/01-project-plan.md` §2.1 倒排表为准（P13 默认不做）。
**演示脚本分层**：插件生命周期演示（P09 交付：安装→停用→卸载）⊂ 端到端演示（P12 交付：三条主链路 + 骨架纯净性）⊂ 答辩演示（P14 固化：`docs/00-feasibility-review.md` §5 十分钟流程，含离线兜底）。

## 阶段总览

| ID | 阶段 | 建议周期 | 依赖 | 状态 |
| --- | --- | --- | --- | --- |
| P00 | 设计基线冻结 | 1-2 天 | 无 | 已完成 |
| P01 | 仓库与工程骨架 | 6-8 天 | P00 | 待开始 |
| P02 | 核心契约与可观测性 | 4-6 天 | P01 | 待开始 |
| P03 | 认证、RBAC 与系统壳 | 5-7 天 | P02 | 待开始 |
| P04 | 元数据写模型 | 5-7 天 | P02 | 待开始 |
| P05 | 动态数据运行时 | 5-7 天 | P04 | 待开始 |
| P06 | 前端动态渲染 | 5-7 天 | P05 | 待开始 |
| P07 | 插件包校验与版本存储 | 4-6 天 | P04、P06 | 待开始 |
| P08 | PluginRuntime 生命周期 | 7-10 天 | P07 | 待开始 |
| P09 | 库存示例插件（演示交付物，不属于系统骨架） | 3-5 天 | P08 | 待开始 |
| P10 | Issue 与规格 Schema | 4-6 天 | P03、P07 | 待开始 |
| P11 | AI 适配器与生成器 | 5-7 天 | P10 | 待开始 |
| P12 | Agent Issue 端到端闭环 | 5-7 天 | P09、P11 | 待开始 |
| P13 | 加分项与体验优化 | 3-5 天 | P12 | 候选 |
| P14 | 质量收敛与答辩交付 | 7-10 天 | P12 | 待开始 |

## 阶段通用质量门槛（QG）

每个阶段除自身验收标准外，还必须满足：

- **QG-1 数值门禁**：格式、lint、type-check 通过；`docs/coding-standards.md` §7 的硬上限零违规，目标值违规需在评审中说明。
- **QG-2 依赖方向**：ArchUnit/import 规则通过；无循环依赖；无跨模块 `infrastructure` 引用。
- **QG-3 扩展点闭环**：新增能力要么复用 `docs/extension-points.md` 已登记扩展点，要么完成 proposed → active 登记闭环，并提供注册/撤销测试。
- **QG-4 单一实现路径**：动态数据、字段类型、renderer、模型调用等能力只有一条 canonical 实现路径。
- **QG-5 契约同步**：API、错误码、迁移、Schema 变化在同一 PR 同步代码、测试、文档和登记册。
- **QG-6 状态同步**：`STATUS.md` 与 `docs/project-status.json` 反映真实进度。
- **QG-7 回归闭环**：本阶段新增能力对应的回归包（`docs/11-regression-test-plan.md` §4）并入并通过；治理自检 R-GOV 无漂移；阶段退出附回归运行证据。
- **QG-8 安全基线**：本阶段涉及的安全条目满足 `docs/13-security-baseline.md` §2 红线（S1-S9）与 §4 阶段映射；新增攻击面必须有对应失败路径测试。

## P00：设计基线冻结

### 实施内容

- 确认 PostgreSQL、JDK、Node 和模型接口。
- 确认库存作为唯一示例业务。
- 冻结 Level 1 声明式插件边界。
- 完成需求、架构、数据模型、测试和仓库规范。

### 产出物

- `README.md`、`docs/00-09`、`AGENTS.md`。
- 已接受的 ADR。
- 根目录 `STATUS.md` 和机器可读状态文件。

### 验收标准

- 新开发者可以从 README 找到当前架构、需求、计划和状态入口。
- MVP 非目标明确写出“禁止任意代码插件”。
- 插件身份、激活状态和失败阶段在架构与数据模型中一致。

## P01：仓库与工程骨架

### 实施内容

- 创建 `backend/`、`frontend/`、`database/`、`plugins/`、`tests/`、`scripts/`。
- 初始化 Spring Boot、Vue 3、TypeScript 和包管理锁文件。
- 添加 Docker Compose、健康检查、配置模板和数据库迁移工具。
- 建立静态检查基线并写入 CI：Checkstyle（`FileLength`/`MethodLength`/`CyclomaticComplexity`/`ParameterNumber`/`NestedIfDepth`）、ESLint（`max-lines`/`max-lines-per-function`/`complexity`/`max-params`）、Prettier、ArchUnit 或等价依赖边界测试；硬上限 = error，目标值 = warn，口径以 `docs/coding-standards.md` §7 为准。
- 添加格式检查、单元测试、依赖边界测试和启动冒烟命令。
- 增加 `scripts/check-repo-health` 脚本：一条命令完成格式、lint、测试、文件上限与文档链接检查。
- 建立回归测试骨架：`tests/fixtures/`（含 `violations/` 故意违规样例）、`fixtures.json` 哈希清单；把 `docs/11-regression-test-plan.md` §3 的 R-GOV-02..06 与 R-GOV-01 简化版（脚本导出 lint 阈值并与 `docs/coding-standards.md` §7 比对）纳入 `check-repo-health`；R-GOV-01 完整解析断言在 P02 交付。
- 增加 `scripts/sync-status`：以 `STATUS.md` 锚点为源单向生成/校验 `docs/project-status.json`，消除人工双写（R-GOV-04 校验保留为门禁）。
- 确认 AI 模型接口可用性（OpenAI 兼容接口 / 学校环境 / 未定）；未确定时按 fixture 优先策略推进（P11 开发与回归不依赖在线模型），结论写入进度日志。
- 落实安全基线 P01 条目：专用数据库账号（非超级用户）、Actuator 端点收敛、CORS 基线与 CSP 配置骨架（`docs/13-security-baseline.md` §4）。

### 验收标准

- 新机器按 README 可启动前端、后端和 PostgreSQL。
- `/actuator/health` 或等价健康接口返回成功。
- 空数据库可以执行 `V001__init.sql`，重复执行不会产生不可解释错误；`database/migrations/` 只含平台骨架表（`sys_*`/`meta_*`/`plugin_*` 等），不含任何业务表。
- CI 或本地检查能明确报告构建、测试、格式、文件上限和依赖边界结果；用一个临时超限文件和一个非法跨模块依赖样例验证 CI 确实会失败，验证后移除样例。
- `scripts/check-repo-health` 可输出 R-GOV-01（简化版）与 R-GOV-02..06 结果；R-GOV-06 对 `tests/fixtures/violations/` 样例稳定报出"门禁已失效"级失败。
- `scripts/sync-status` 在修改 `STATUS.md` 锚点后可单向生成 JSON，R-GOV-04 校验通过；模型接口可用性结论已记录（或显式标注"待确认"）。
- 骨架代码本身满足全部硬上限与依赖方向规则。

## P02：核心契约与可观测性

### 实施内容

- 实现统一 ID、分页、错误响应、request ID 和结构化日志；本周内冻结口径：内部主键 `bigint`、对外业务 ID 为不透明字符串、`pluginId/pluginVersionId/activationId/requestId` 均为稳定字符串格式；分页默认 `pageSize=20`、上限 `200`，排序字段必须来自白名单。
- 定义 `ServiceKey`、`PluginContext`、`Registration`、`DomainEvent` 的最小接口。
- 实现内存 `ServiceRegistry`、`ExtensionRegistry` 和基础事件发布器。
- 建立审计事件写入端口。
- 把 `docs/extension-points.md` 的 v1 清单落地为代码常量/枚举；`ServiceKey`、`ExtensionPoint` ID、`DomainEventType` 必须使用登记册 ID。
- 为跨模块公开接口添加 `@PublicApi` / `@ExperimentalApi` 标注。
- 交付 R-GOV-01 完整版：解析 Checkstyle/ESLint 配置，断言阈值与 `docs/coding-standards.md` §7 完全一致（从 P01 简化版升级）。

### 验收标准

- 任一 API 错误都返回稳定错误码、消息和 request ID。
- 注册后可以读取贡献；关闭 `Registration` 后贡献不可见。
- 同一注册项重复关闭不会报错。
- 日志不包含密码、JWT、API key 和完整 Authorization 头。
- `docs/extension-points.md` 中每个 active 扩展点都有定义、至少一个真实消费方和注册-撤销测试。
- 依赖边界测试拒绝未标注接口的跨模块引用。

## P03：认证、RBAC 与系统壳

### 实施内容

- 实现登录、退出、密码哈希和 JWT 校验。
- 创建管理员、开发者、普通用户三类角色和最小权限集合。
- 实现用户、角色、菜单、当前用户接口。
- 记录登录、权限变化和关键管理操作。
- 提供最小审计事件查询接口（按时间、操作者、对象过滤），支撑演示流程"查看关键操作审计日志"（`docs/00-feasibility-review.md` §5 步骤 6）。
- JWT 有效期可配置；演示/开发环境 TTL 覆盖完整演示时长，避免演示中途令牌过期。

### 验收标准

- 三类角色在接口和菜单上表现不同。
- 过期 JWT、无效 JWT 和无权限请求均返回可诊断错误。
- 管理员可以创建用户并分配角色；普通用户不能修改权限。
- 关键写操作能在审计日志中追溯到操作者和结果。

## P04：元数据写模型

### 实施内容

- 建立 `meta_entity`、`meta_field`、`meta_view` 及关联迁移。
- 实现六类字段白名单、字段名规范化、默认值和校验规则；类型映射集中在 `FieldTypeRegistry`，校验、SQL 映射与前端 renderer 选择都从 registry 取，禁止在 Controller/模板中散落字段类型分支。
- 实现 `MetaRegistry` 查询和缓存失效机制。
- 元数据变更规则：实体启用且有数据后，字段重命名/类型变更视为 breaking（需要迁移方案或 ADR，禁止静默改）；新增字段/视图配置为 additive，可直接执行。
- 提供实体、字段、视图配置 API。

### 验收标准

- 开发者可以创建、修改、停用实体和字段。
- 非法字段名、类型、renderer ID 和校验规则在 API 边界被拒绝。
- 普通用户只能读取已启用且有权限的元数据。
- 元数据变更会产生审计事件，并能让前端重新获取最新版本。
- 字段类型映射只有 `FieldTypeRegistry` 一处（由测试断言）；未来新增字段类型不需要修改动态 CRUD 主路径。
- 已有数据的实体字段重命名/类型变更被拒绝或要求明确迁移方案，不会静默改变数据语义。

## P05：动态数据运行时

### 实施内容

- 实现受控动态实体记录存储和 JSONB 数据访问。
- 实现字段映射、参数绑定、排序和分页白名单。
- 实现新增、编辑、删除、详情和查询接口。
- 删除采用物理删除 + 审计事件；MVP 不实现软删除（未来需要时以迁移引入），不得绕过 `service.data-access` 自行删数据。
- 接入字段级校验和实体级业务规则。

### 验收标准

- 使用 P04 创建的实体可以完成完整 CRUD。
- 用户输入不会被当作 SQL、列名或表名直接执行。
- 库存数量小于 0 等非法数据被拒绝。
- 主要 CRUD 接口在演示数据量下 P95 小于 500 ms。
- 动态数据读写只经 `service.data-access`；没有为示例实体手写专用 Controller/SQL。
- 删除操作产生审计事件；`pageSize` 超过 200 被拒绝并返回可诊断错误。

## P06：前端动态渲染

### 实施内容

- 实现菜单 registry、字段 renderer registry 和动态列表/表单/详情页面；registry key 使用 `extension.navigation`、`extension.field-renderer`、`extension.record-action` 登记册 ID。
- 实现 layout registry 与 theme registry：消费 `extension.layout` 布局贡献（页面/工作台槽位与部件编排）与 `extension.theme-asset` 美术资产贡献（背景图/图标/动画，FR-PLUGIN-10/11）；缺省布局与平台默认外观兜底，贡献撤销后即时恢复。
- 支持 loading、empty、error、permission denied 和 stale 元数据状态。
- 将 API service、页面组合和通用 renderer 分层。
- 根据实体/视图版本刷新页面，不把元数据当作 HTML 或脚本执行。

### 验收标准

- 无需新增业务页面代码即可显示 P05 的实体列表和表单。
- 文本、数字、日期、枚举、布尔字段使用正确内置 renderer。
- 菜单贡献可注册和撤销，撤销后不残留入口。
- 布局贡献按声明渲染页面槽位与部件编排，撤销后恢复缺省布局（FR-PLUGIN-10）。
- 美术资产（背景图/图标/动画）经插件贡献更换即时生效，停用/卸载后恢复平台默认外观（FR-PLUGIN-11）。
- 浏览器刷新后页面仍能从服务端恢复元数据。
- 新增一个内置 renderer 只需登记映射，不修改动态列表/表单通用组件主逻辑。

## P07：插件包校验与版本存储

### 实施内容

- 实现 `plugin.json` Schema、路径、大小、哈希和能力等级校验。
- 建立 `plugin_instance`、`plugin_version`、`plugin_dependency` 表。
- 保存不可变 manifest 和 content hash。
- 实现依赖解析、安装预览和幂等导入。
- 校验器按 `plugin.json.schemaVersion` 分派；`contributions` 键与 renderer ID 必须能在 `docs/extension-points.md` 中查到。
- 实现插件迁移 runner 骨架：`V*__*.sql` 顺序执行、checksum、`plugin_migration` 记录、与安装同事务、重复跳过（ADR-0005）；插件 `migrations/` 不挂入 Flyway。
- 上传安全基线：单包压缩大小上限 10 MB、解压后总大小上限 50 MB（防 zip 炸弹）、zip-slip 防护、解压到临时目录并在校验/导入后清理、非法包隔离不落库。
- 美术资产校验（FR-PLUGIN-11）：`assets/` 静态资源类型白名单（图片 png/svg/webp；动画 css/lottie json 等声明式格式，禁止可执行脚本）、单文件与总量大小上限、路径校验；`extension.theme-asset` 贡献的 path 必须解析到包内已校验资产。

### 验收标准

- 合法 Level 1 插件可以导入并生成预览。
- 缺失字段、越权 renderer、路径穿越和任意脚本资源被拒绝。
- 同一内容重复导入不会生成重复版本。
- 依赖缺失能指出具体插件和版本范围。
- 未知 `schemaVersion` 返回 `unsupported_schema_version`；登记册外的贡献类型或 renderer ID 被拒绝。
- 超过压缩或解压后大小上限、zip-slip 路径和非 `V*__*.sql` 迁移资源被拒绝，临时目录无残留。
- 迁移脚本越界修改平台表被 runner 校验拒绝；checksum 变化的同版本重复导入被拒绝。

## P08：PluginRuntime 生命周期

### 实施内容

- 建立 `plugin_activation`、`plugin_registration`、`plugin_migration`、`plugin_audit_event`。
- 实现 `DEFINED/VALIDATED/INSTALLED/STARTING/ACTIVE/STOPPING/STOPPED/FAILED` 状态迁移。
- 创建 `ActivationContext`，绑定菜单、权限、元数据、renderer 和事件注册。
- 实现迁移事务、失败回滚、停止清理和 stale activation 拒绝。

### 验收标准

- 激活成功后插件贡献可用，停用后全部撤销。
- 缺依赖、迁移失败和注册冲突均记录失败阶段并清理残留。
- 同一插件同一时间只能有一个 STARTING/ACTIVE 激活。
- 使用旧 activation ID 的请求返回 `stale_activation`，不影响当前版本。
- 升级失败时 current 版本仍可用。
- 迁移失败整体回滚且 `plugin_migration` 无残留记录；重复安装跳过已应用脚本。

## P09：库存示例插件

### 实施内容

- 创建 `plugins/example-inventory` 声明式插件。
- 以可导入的普通 Level 1 包预置：不写入 backend/frontend 编译产物，不做自动安装；演示脚本中的安装步骤只调用标准插件导入/安装 API。
- 定义物料编码、名称、库存数量、状态和库存规则。
- 注册导航项、实体、列表视图和表单视图。
- 编写种子数据（作为插件资源或可重复脚本）、安装说明和演示脚本；演示脚本必须覆盖 安装 -> 停用 -> 卸载 的完整生命周期。

### 验收标准

- 干净数据库安装插件后出现库存菜单。
- 普通用户可以完成库存记录查询和编辑。
- 库存数量非负规则有效。
- 停用/启用/卸载插件后菜单、权限和数据行为符合文档。
- 库存插件与第三方插件同权：只能通过插件管理启停/卸载；干净数据库只执行平台迁移时不出现库存菜单、实体或页面（NFR-SKEL-01）。
- 卸载后菜单、权限、renderer 注册全部撤销，审计记录保留。

## P10：Issue 与规格 Schema

### 实施内容

- 实现 Issue、评论、标签、指派和状态机。
- 定义版本化 `RequirementSpec` JSON Schema。
- 将实体、字段、视图、权限、业务规则和验收标准纳入规格。
- 实现审核、退回、测试反馈和关闭原因。

### 验收标准

- Issue 只能通过合法状态迁移推进。
- 规格缺少必要字段时不能进入批准状态。
- 评论、状态变化和规格版本均可审计。
- 开发者可以从 Issue 预览将要生成的插件资源。

## P11：AI 适配器与生成器

### 实施内容

- 定义 OpenAI 兼容模型端口和 fixture 端口。
- 实现多轮澄清提示词、输出 Schema 校验和有限重试。
- 将确认后的规格转换为 Level 1 插件包。
- 保存模型、提示词版本、输入规格和输出校验结果。
- 模型访问收敛在 `ModelPort` 端口，HTTP 与 fixture 两种实现；生成器只依赖端口，不感知具体供应商。fixture 优先：模型接口未最终确认前，开发与回归只依赖 fixture 端口，在线路径在接口确认后接入。
- 提示词模板按版本文件化（如 `prompts/v1/*.md`），代码中不散落提示词字符串；fixture 与提示词版本一一对应。
- 模型请求设置超时与取消；模型密钥只经环境变量/`.env` 注入，仓库只提交 `.env.example`（占位值）。

### 验收标准

- 模型可用时能生成合法 RequirementSpec。
- 模型不可用时可以手工编辑规格并继续流程。
- 非法 JSON、越权 renderer 和未知字段不会生成可安装包。
- AI 不获得数据库、shell、文件系统或生产发布权限。
- 切换/新增模型实现不需要修改 Issue、规格 Schema 或生成器主流程。
- 超时、取消与限流失败返回可诊断错误，任务可回到可操作状态；日志与数据库不保存模型密钥和完整请求头。
- 每次模型调用与生成尝试均有结构化记录（模型、提示词版本、澄清轮次、重试次数、输出校验结果、耗时）并落库，可通过导出脚本产出论文实验数据集（`docs/12-thesis-experiment-plan.md`）。

## P12：Agent Issue 端到端闭环

### 实施内容

- 串联 Issue 创建、AI 澄清、人工确认、插件生成、审核、测试安装和完成。
- 增加任务状态、重试上限、取消和失败恢复。
- 建立端到端演示脚本和固定模型 fixture。
- 在插件页显示生成版本、激活尝试和失败诊断。

### 验收标准

- 在干净数据库上连续三次完成完整演示；各环节实测耗时有记录，作为论文与答辩数据（对照 `docs/00-feasibility-review.md` §5 十分钟目标）。
- AI 失败、插件验证失败和安装失败都能回到可操作状态。
- Issue、插件版本、activation 和审计事件可以互相追踪。
- 人工审核之前不会启用生成插件。

## P12.5：前端基建与默认主题（2026-08-30 用户裁决新增）

> 背景：P14 验收（2026-08-30）发现三缺陷 + 前端体验缺口，用户裁决在 P13 前插入。
> **架构红线：结构与皮分离**——按钮/开关/组件卡/抽屉/动画/排版属**平台前端基建**（内置组件库，骨架朴素可用，NFR-SKEL-01 不破）；**SVG 美术资源与主题参数（颜色/图标/背景/动效参数）以 Level 1 主题插件分发**（themeAssets → CSS 变量），安装后呈现完整视觉，且**可被其他插件同名 themeAssets 覆盖自定义**（FR-PLUGIN-11 既有语义）。不引入新扩展点（QG-3：全部复用 extension.theme-asset / extension.layout / extension.widget）。

### 实施内容

- 验收缺陷修复：①菜单路由对齐后端契约（`/workbench`、`/system/users` 前端缺失致 SPA 兜底重定向回工作台）②`EntityCards` 只渲染 `enabled` 实体（特权角色全量列表把 disabled 实体渲染成 404 入口）③账号获取路径：`/system/users` 用户管理页（ADMIN 建号/角色，P03 后端 API 消费方）；自助注册与账号停启用不纳入（docs/13 攻击面边界与裁剪，记 P13 候选）。
- 前端基建组件库：BaseButton/BaseSwitch/ComponentCard/BaseDrawer + 过渡动画（页面切换/开关/抽屉，尊重 `prefers-reduced-motion`）+ 排版 tokens（间距/字号/圆角/阴影 CSS 变量基线）。
- 默认主题插件 `plugins/theme-default`：SVG 美术资源（图标/背景/动画参数）+ themeAssets 声明；工作台壳消费扩展 CSS 变量。
- 主题自定义验证：轻量第二主题插件覆盖同名变量，证明"其他插件可自定义前端样式"。

### 验收标准

- 三缺陷关闭：系统管理页可建号并登录；全部菜单点击不再落到工作台；特权视角无 404 实体入口。
- 组件库有渲染测试（RB-UI 扩展）；不装主题插件时骨架朴素可用（R-GOV-09 无硬编码），安装后完整主题呈现，卸载恢复基线。
- 主题插件 A 安装后，插件 B 覆盖同名变量可改变样式（注册/撤销测试）。
- 门禁 0 fail；RB-UI/RB-E2E 回归全绿。

## P13：加分项与体验优化（候选）

> 2026-08-30 用户裁决改写范围：原"动作链工作流或插件市场"不做，改为消化 P12.5 裁剪的三个体验项（自助注册/账号停启用/主题热切换），均已在此前各阶段以"记 P13 候选"登记。

### 实施内容

- **自助注册**（用户验收缺陷①的另一半）：`POST /api/v1/auth/register`（匿名），默认 USER 角色；开关 `flexforge.auth.self-registration-enabled`（默认开，env 可关=feature flag）；IP 维度注册限流；注册即登录下发令牌；审计 `auth.register`。
- **账号停启用**：`PUT /system/users/{id}/status`（ADMIN，不可操作自己）；BLOCKED 登录拒绝（既有 `user.active()` 语义）；前端用户管理页开关组件。
- **主题热切换**：路由切换时重拉 theme-assets 聚合并差量撤销消失激活的贡献（免整页刷新）。
- 优化加载、空状态、错误诊断、筛选和安装预览（随上述顺带）。

### 验收标准

- 不改变 P12 的核心契约和数据库迁移（auth/register 与 status 端点为 additive）。
- 可选功能关闭时主流程完全可用（注册开关关闭→端点拒绝且登录页回隐藏注册入口）。
- 已知限制如实登记：BLOCKED 用户已持有的 JWT 在 TTL 内仍有效（无状态令牌，docs/13 记录）；注册限流为单实例内存口径。
- 若影响主线稳定性，立即撤销本阶段。

## P14：质量收敛与答辩交付

### 实施内容

- 完成单元、集成、安全、E2E 和性能基线。
- 按 `docs/11-regression-test-plan.md` §2 执行 L4 发布候选全量回归（干净环境重建 + 全量回归包 + 演示脚本），运行证据写入进度日志。
- 在干净机器验证 Docker Compose 和数据库迁移。
- 固化演示账号、种子数据、录屏、架构图和故障排查手册。
- 离线答辩三保险：演示机提前导入全部 Docker 镜像（不依赖现场网络）、模型演示走 fixture 模式、完整演示录屏兜底。
- 整理论文实验数据和已知限制。

### 验收标准

- 核心验收场景全部通过，P0/P1 缺陷为零。
- 新环境按文档可以启动并完成演示。
- 答辩演示不依赖在线模型；在线模型只作为增强路径。
- `STATUS.md` 标记项目进入 `release_candidate`，并记录最后一次全量验证时间。

## P15：平台体验补全与插件生态演示（2026-08-30 用户裁决新增）

> 背景：P13 完成后用户验收提出五项缺口：①前端无 Issue/AI 入口（用户与 AI 对话不可达）②插件管理只有只读清单，缺安装/启停/卸载操作③登录页与工作台视觉粗糙（背景/品牌/动画缺失）④示例业务插件不足，且无"界面可自定义"演示⑤无设置页（AI 模型配置、语言等）。
> **红线（延续 P12.5 结构与皮分离）**：UI 文案 key 化、设置页框架、登录页平台品牌基线属**平台基建**；语言包与美术资产以 Level 1 插件分发（复用 extension.theme-asset 通道）；AI API Key 存储扩展须先登记 docs/13 §3.6 再实现。

### 实施内容

- **Issue/AI 工作台前端**（FR-ISSUE-01..06 消费面）：Issue 列表/创建/评论/标签；**AI 对话入口**=clarify 问答（作者或开发者发起，回答追问→生成/迭代规格草稿）；开发者：状态迁移/规格查看与手工编辑（模型不可用兜底）/预览/生成骨架。
- **插件管理写操作**：PluginsView 整合 zip 上传（validate→import）、按版本激活、停用、卸载（ADMIN-only，与 FR-PLUGIN-01/02 契约一致；失败 stage+errorCode 就地诊断）。
- **设置页**：AI 模型运行时配置（provider=fixture|http、base-url、model、API Key——加密存储、任何读接口只回掩码与"已配置"位）+ 界面语言切换（locale 插件消费面）。
- **i18n 通道**：extension.theme-asset kind 扩 `locale`（JSON 文案表，键=界面文案 key 或 `menu.<key>`）；前端 localeRegistry+`t()`；平台内置 zh-CN 基线（内置=结构，语言内容=皮）；示例插件 plugins/locale-en 演示"其他插件自定义界面"。
- **登录页/工作台品牌化**：平台品牌基线 SVG（logo/几何背景）+ 入场动画（尊重 prefers-reduced-motion）；登录页无会话不能取签名主题资产，维持平台基线（已知限制登记）。
- **示例业务插件补充**：≥2 个不同形态（字段类型/视图组合差异），与库存示例同装共存，证明元数据驱动泛化。

### 验收标准

- Issue 全链可从 UI 走通：用户提交→AI clarify 问答出规格→开发者批准→生成→插件页可见生成版本。
- 插件安装/启停/卸载全程 UI 可操作、失败可诊断；非管理员无写入口。
- AI 配置经设置页生效（http 模式读运行时配置）；API Key 任何接口不回显明文；模型不可用仍可手工规格兜底（FR-ISSUE-06 回归）。
- 英文插件激活后设置页可切 en 且界面即时切换；停用插件语言回退 zh-CN。
- 新示例插件过标准包校验、与既有示例同装；门禁 0 fail、前后端回归全绿。

## P16：前端体验打磨与默认主题精修（2026-08-31 用户裁决新增）

> 背景：P15 完成后用户验收提出三点：①前端操作逻辑仍有大量繁琐、不符合人类使用直觉的交互；②UI 文案不得出现含括号的解释性内容；③默认主题需精致统一（允许使用网络美术资源，须本地化内置）。
> **红线**：延续结构与皮分离——交互骨架与默认主题基线（tokens.css/App 壳层）属**平台基建**，主题插件覆盖机制与 locale 通道不变；不新增后端端点、不扩 MVP（不加列表搜索等新契约）；网络资源一律下载入仓或 npm 自托管打包，**禁止运行时外链**（CSP `default-src 'self'`，docs/13 §3.4；S5 无脚本执行不变）；新增依赖须在 PR 说明。

### 实施内容

- **交互重构（迭代 1）**：统一确认对话框组件替代全部原生 `window.confirm`（记录删除/插件卸载/生成激活）；Issue 状态迁移从"下拉选目标+填原因+执行"改为**可用迁移按钮组**（主链推进一键直达，旁路/回退在对话框内填原因）；插件上传从原生 file input 两按钮改为**拖拽/点击上传区+选包自动校验**（通过后启用导入）；动态实体列表"新增记录"入口从页脚上移至列表头部、空态提供创建引导；设置页语言卡去冗余 select；AI 对话新消息自动滚动到底。
- **文案净化（迭代 1）**：全部 UI 文案禁括号解释——错误附注改"消息 · 追踪码 xxx"统一格式（apiErrorMessage 助手收敛各视图手拼）；设置页 provider 选项、密钥 placeholder、stale 告警、保存提示、分页计数、迁移原因、spec 编辑器提示、导入幂等提示、枚举空选项等逐条改写；locale-en 语言包同步。
- **默认主题精修（迭代 2）**：设计令牌精化（主色阶/强调色/焦点环/阴影分层/输入态）；侧栏导航分组（平台/示例应用）+内联 SVG 图标+激活指示；表单控件统一样式（focus/hover/disabled）；表格精修（表头层次/行 hover）；实体入口卡信息层次；字体本地化（拉丁用自托管 Inter，中文维持系统栈，无外链）；全局观感与动效一致性走查。

### 验收标准

- 前端源码 UI 文案不再含中文括号解释（模板/提示串全量扫描通过；测试断言同步）。
- 破坏性操作全部走统一确认对话框（无原生 confirm）；Issue 迁移、插件导入、实体创建的点击步数较改前减少且无需在"执行前"预理解规则。
- 默认主题（停用全部主题插件）视觉走查：导航分组与图标、控件焦点态、表格/卡片层次达到统一精致；主题插件覆盖机制回归不受影响。
- locale-en 切换后新文案正确呈现英文；门禁 0 fail、前后端回归全绿。

## P17：看板视图与插件声明式扩展（2026-09-01 用户裁决新增）

> 背景：P16 完成后用户提出希望有"处理表格的插件"与"看板类型的插件"；经方案澄清用户裁决本轮交付**看板视图+示例插件**（CSV 导出/页脚聚合/批量操作为候选后续）。机制前提：插件纯声明（S5 不带代码），新视图类型=平台内置渲染器（结构），插件 manifest 声明即得（皮）。
> **红线**：ViewType 白名单经 V013 迁移扩展（CHECK + group_by 列）；视图契约（extension-points §视图类型）先登记再实现；看板第一版**只读**（卡片点击进详情编辑分组字段，不做拖拽换列）；声明式边界不变——groupBy/卡片列全部来自元数据，无脚本。

### 实施内容

- **视图类型 kanban（平台基建）**：ViewType 枚举 + meta_view 迁移（V013：view_type CHECK 扩 kanban + group_by 列）；ViewRules 校验——kanban 视图 groupBy 必填且必须是同实体 enum 字段，columns 语义=卡片显示字段；元数据写模型 ViewCommand/view 定义/查询链路全链路透传 groupBy。
- **插件视图注册扩展**：PluginLifecycleService 视图白名单扩 kanban，spec {entity,viewType,name,groupBy,columns,filters?} 经同一 ViewRules 校验后 upsert。
- **前端看板渲染**：ViewDefinition 契约 +kanban/groupBy；实体有 kanban 视图时列表页头部"列表/看板"切换；KanbanView 组件——groupBy 枚举选项为列（含"未设置"列），列头计数，卡片按 columns 渲染字段值（复用 display renderer），点击进详情；看板模式单页加载 100 条。
- **示例插件 example-kanban**：pipeline_task 实体（标题/阶段 enum 待办·进行中·已完成/负责人/优先级 enum/截止日期）+ list/form/kanban 三视图 + 种子数据，证明"插件声明即得看板"。

### 验收标准

- example-kanban 导入激活后，其实体页可在列表/看板间切换：看板按阶段分列、列头计数正确、卡片点击进详情；编辑阶段字段后返回看板列变化。
- 非法声明被拒：kanban 视图缺 groupBy、groupBy 指向非 enum 字段或不存在字段，包校验/激活注册失败路径有测试。
- 元数据管理 API 对 kanban 视图与 list/form 同口径（创建/编辑/viewType 不可改）；既有 list/form 视图与既有示例插件回归不受影响。
- 门禁 0 fail、前后端回归全绿；登记册（extension-points 视图类型契约）与本节同步。

## P18：前端现代极简风格化（2026-09-03 用户裁决新增）

> 背景：P17 出口验收后用户裁决对前端整体做**现代极简风格化**——观感对齐当代极简设计语言（中性灰阶、发丝线分隔、克制的单一主色、平面化层次）；优先复用网络公开资源（色板/字体/图标），不从头手搓。
> **红线（骨架与主题包分工）**：延续结构与皮分离——**骨架（平台基建）**= `frontend/src/styles/tokens.css` 设计令牌基线与 `base.css` 全局观感、LoginView 结构样式（排版/间距/控件形态/层次结构，属组件与排版结构，不随主题变化）；**主题包**= `plugins/theme-default`/`theme-warm` 的 tokens 键值与美术资产（色彩身份与背景，可整体换肤）。UI 文案只记录现有实现，不新增标注与解释性内容；不新增后端端点、不扩 MVP、无运行时外链（CSP 不变）；色板值取自公开设计体系（Tailwind v4 默认色板，MIT）并注明来源。

### 实施内容

- **色板资源引入（现成资源优先）**：从 GitHub tailwindlabs/tailwindcss v4 默认主题取 zinc 中性阶与 indigo 主色阶精确值，作为骨架令牌基线取值来源（值入仓于 tokens.css 注释，无运行时依赖）；字体沿用自托管 Inter、图标沿用 lucide 风格内联集（P16 已内置）。
- **骨架令牌重订（tokens.css）**：中性色改 zinc 阶（背景/表面/文字/边框/发丝线）；主色收敛为单一 indigo 阶（hover/soft/ink 派生）；侧栏令牌改浅色平面体系（派生公式面向浅底：ink-strong 加深、hover/active 浅底中性；深色侧栏主题需连带覆盖派生键——键集不变，仅注释契约明确）；圆角/阴影/焦点环精修（边框优先层次、更轻投影）；排版与动效令牌微调。
- **骨架全局观感（base.css）**：侧栏改浅色平面+右侧发丝线（去渐变），导航激活态 pill+强调条；表格去容器投影改发丝线分隔+行 hover 中性化；表单控件边框/焦点环统一；卡片层次降投影。
- **骨架登录页极简（LoginView）**：移除极光渐变漂移动画，改纯色底+发丝线卡片；结构不变（双模式/语言通道保留）。
- **主题包同步（皮）**：theme-default tokens 键值对齐新基线、bg.svg 重绘为极弱底纹、版本 1.0.0→1.1.0；theme-warm tokens 适配新键集（暖色覆盖示例语义不变）。

### 验收标准

- 全站视觉走查（登录/工作台/实体列表与看板/Issue/插件/设置/用户）：中性灰阶+单一主色+发丝线层次的现代极简观感统一，无残留旧蓝色系/深色渐变侧栏元素。
- 骨架与主题包分工可验证：停用全部主题插件时骨架基线自洽完整；激活 theme-default 后色彩身份一致不回退；theme-warm 覆盖生效（结构与排版不随主题变化）。
- 主题插件覆盖机制回归（tokens 通道/热切换/撤销恢复）不受影响；前端回归全绿、门禁 0 fail。
- UI 文案无新增标注或解释性内容（文案基线不随风格化变更）。
- 登记册消费方条目（extension.theme-asset）与本节同步。
