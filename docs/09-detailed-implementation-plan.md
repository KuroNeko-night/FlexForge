# FlexForge 详细实施阶段计划

**计划基线**：2026-08-20
**阶段编号**：`P00` - `P14`
**阶段规则**：前一阶段未达到退出条件，不进入后一阶段；加分项不得阻塞主线。

## 阶段总览

| ID | 阶段 | 建议周期 | 依赖 | 状态 |
| --- | --- | --- | --- | --- |
| P00 | 设计基线冻结 | 1-2 天 | 无 | 已完成 |
| P01 | 仓库与工程骨架 | 3-5 天 | P00 | 待开始 |
| P02 | 核心契约与可观测性 | 3-5 天 | P01 | 待开始 |
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
- 建立回归测试骨架：`tests/fixtures/`（含 `violations/` 故意违规样例）、`fixtures.json` 哈希清单；把 `docs/11-regression-test-plan.md` §3 的 R-GOV-01..06 纳入 `check-repo-health`。

### 验收标准

- 新机器按 README 可启动前端、后端和 PostgreSQL。
- `/actuator/health` 或等价健康接口返回成功。
- 空数据库可以执行 `V001__init.sql`，重复执行不会产生不可解释错误；`database/migrations/` 只含平台骨架表（`sys_*`/`meta_*`/`plugin_*` 等），不含任何业务表。
- CI 或本地检查能明确报告构建、测试、格式、文件上限和依赖边界结果；用一个临时超限文件和一个非法跨模块依赖样例验证 CI 确实会失败，验证后移除样例。
- `scripts/check-repo-health` 可输出 R-GOV-01..06 结果；R-GOV-06 对 `tests/fixtures/violations/` 样例稳定报出"门禁已失效"级失败。
- 骨架代码本身满足全部硬上限与依赖方向规则。

## P02：核心契约与可观测性

### 实施内容

- 实现统一 ID、分页、错误响应、request ID 和结构化日志；本周内冻结口径：内部主键 `bigint`、对外业务 ID 为不透明字符串、`pluginId/pluginVersionId/activationId/requestId` 均为稳定字符串格式；分页默认 `pageSize=20`、上限 `200`，排序字段必须来自白名单。
- 定义 `ServiceKey`、`PluginContext`、`Registration`、`DomainEvent` 的最小接口。
- 实现内存 `ServiceRegistry`、`ExtensionRegistry` 和基础事件发布器。
- 建立审计事件写入端口。
- 把 `docs/extension-points.md` 的 v1 清单落地为代码常量/枚举；`ServiceKey`、`ExtensionPoint` ID、`DomainEventType` 必须使用登记册 ID。
- 为跨模块公开接口添加 `@PublicApi` / `@ExperimentalApi` 标注。

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
- 支持 loading、empty、error、permission denied 和 stale 元数据状态。
- 将 API service、页面组合和通用 renderer 分层。
- 根据实体/视图版本刷新页面，不把元数据当作 HTML 或脚本执行。

### 验收标准

- 无需新增业务页面代码即可显示 P05 的实体列表和表单。
- 文本、数字、日期、枚举、布尔字段使用正确内置 renderer。
- 菜单贡献可注册和撤销，撤销后不残留入口。
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
- 上传安全基线：单包大小上限 10 MB、zip-slip 防护、解压到临时目录并在校验/导入后清理、非法包隔离不落库。

### 验收标准

- 合法 Level 1 插件可以导入并生成预览。
- 缺失字段、越权 renderer、路径穿越和任意脚本资源被拒绝。
- 同一内容重复导入不会生成重复版本。
- 依赖缺失能指出具体插件和版本范围。
- 未知 `schemaVersion` 返回 `unsupported_schema_version`；登记册外的贡献类型或 renderer ID 被拒绝。
- 超过大小上限、zip-slip 路径和非 `V*__*.sql` 迁移资源被拒绝，临时目录无残留。
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
- 模型访问收敛在 `ModelPort` 端口，HTTP 与 fixture 两种实现；生成器只依赖端口，不感知具体供应商。
- 提示词模板按版本文件化（如 `prompts/v1/*.md`），代码中不散落提示词字符串；fixture 与提示词版本一一对应。
- 模型请求设置超时与取消；模型密钥只经环境变量/`.env` 注入，仓库只提交 `.env.example`（占位值）。

### 验收标准

- 模型可用时能生成合法 RequirementSpec。
- 模型不可用时可以手工编辑规格并继续流程。
- 非法 JSON、越权 renderer 和未知字段不会生成可安装包。
- AI 不获得数据库、shell、文件系统或生产发布权限。
- 切换/新增模型实现不需要修改 Issue、规格 Schema 或生成器主流程。
- 超时、取消与限流失败返回可诊断错误，任务可回到可操作状态；日志与数据库不保存模型密钥和完整请求头。

## P12：Agent Issue 端到端闭环

### 实施内容

- 串联 Issue 创建、AI 澄清、人工确认、插件生成、审核、测试安装和完成。
- 增加任务状态、重试上限、取消和失败恢复。
- 建立端到端演示脚本和固定模型 fixture。
- 在插件页显示生成版本、激活尝试和失败诊断。

### 验收标准

- 在干净数据库上连续三次完成完整演示。
- AI 失败、插件验证失败和安装失败都能回到可操作状态。
- Issue、插件版本、activation 和审计事件可以互相追踪。
- 人工审核之前不会启用生成插件。

## P13：加分项与体验优化（候选）

### 实施内容

- 只选择一个：轻量动作链工作流或内部插件市场；所选功能必须置于 feature flag 后，关闭时主线不受影响。
- 优化加载、空状态、错误诊断、筛选和安装预览。

### 验收标准

- 不改变 P12 的核心契约和数据库迁移。
- 可选功能关闭时主流程完全可用。
- 若影响主线稳定性，立即撤销本阶段。

## P14：质量收敛与答辩交付

### 实施内容

- 完成单元、集成、安全、E2E 和性能基线。
- 按 `docs/11-regression-test-plan.md` §2 执行 L4 发布候选全量回归（干净环境重建 + 全量回归包 + 演示脚本），运行证据写入进度日志。
- 在干净机器验证 Docker Compose 和数据库迁移。
- 固化演示账号、种子数据、录屏、架构图和故障排查手册。
- 整理论文实验数据和已知限制。

### 验收标准

- 核心验收场景全部通过，P0/P1 缺陷为零。
- 新环境按文档可以启动并完成演示。
- 答辩演示不依赖在线模型；在线模型只作为增强路径。
- `STATUS.md` 标记项目进入 `release_candidate`，并记录最后一次全量验证时间。
