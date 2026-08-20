# FlexForge 工程质量与可扩展性治理基线

**版本**：v1.0
**状态**：active（P01 起生效）
**日期**：2026-08-20
**适用范围**：`backend/`、`frontend/`、`database/`、`plugins/`、`tests/`、`scripts/` 的全部源码、配置、迁移与插件资源

> 本文回答两个问题：代码如何保持可读、模块化、不会长成屎山；未来能力从哪里接入、现在为未来留什么、留多少。
> 数值约束的唯一来源是 `docs/coding-standards.md` §7，本文只引用不复制，避免两处规则漂移。

## 1. 目标

1. **可读**：任何文件 5 分钟内能定位主流程；方法名说清职责；"为什么"靠注释，"是什么"靠代码。
2. **模块化**：每个模块一个职责、一个 owner；依赖方向唯一；跨模块只能走公开契约。
3. **可扩展**：新能力优先"注册到既有扩展点"，而不是"改老代码加 if"；扩展点少而清晰、可撤销。
4. **不投机**：为未来预留的是 seam（接口、注册表、版本位、开关），不是死代码和空表字段。

## 2. 反屎山红线（R1-R9）

- **R1 依赖方向**：`api -> application -> domain -> infrastructure`；禁止反向与循环依赖，CI 由 ArchUnit / import 规则阻断。
- **R2 数值门禁**：文件长度、复杂度、参数、嵌套按 `docs/coding-standards.md` §7 执行；硬上限 CI 报 error，目标值报 warn。
- **R3 单一实现路径**：同一能力只能有一个 canonical owner。动态数据读写只能走 `service.data-access`；字段类型映射只能走 `FieldTypeRegistry`；模型调用只能走 `ModelPort`。任何"手写专用 Controller 绕开动态数据"或"复制一套实现"都被评审拒绝。业务能力（含演示示例）禁止硬编码进平台模块，只能作为 Level 1+ 插件交付（ADR-0004）。
- **R4 注册即可撤销**：所有运行时注册项绑定 `activationId` 并提供 disposer；停用/卸载后不得残留（`NFR-PLUGIN-01`）。
- **R5 扩展点先登记后使用**：`docs/extension-points.md` 是唯一登记册；未登记扩展点不得合并。
- **R6 公开契约显式标注**：跨模块公开接口标注 `@PublicApi`（稳定）或 `@ExperimentalApi`（可变更，必须注明变更窗口）；未标注视为内部实现，禁止跨模块依赖。
- **R7 契约与实现同提交**：API、错误码、数据库结构、`plugin.json` / `RequirementSpec` Schema 任一变化，代码、迁移、测试、文档与登记册在同一 PR 完成；破坏性变更必须有 ADR。
- **R8 不留死代码与虚假完成**：`TODO/FIXME` 必须携带 Issue 号；dead code 发现即删除或登记理由；任何阶段不得把"写了但未验证"记为完成。
- **R9 可选功能必须可关闭**：P13 及以后的可选能力必须用 feature flag 包裹，关闭时主线不受影响（对齐 P13 验收标准）。

## 3. 现在为未来预留的 seam（留什么、不留什么）

| 未来方向 | 现在必须建的 seam | 现在明确不建 | 引入阶段 | 触发条件 |
| --- | --- | --- | --- | --- |
| 更多字段类型 | `FieldTypeRegistry` 单点映射：校验、SQL 映射、renderer 选择都从 registry 取 | 不预建第 7 种字段类型及其 UI | P04/P05/P06 | 真实需求出现时：登记类型 + 校验 + renderer + 测试，不改动态 CRUD 主路径 |
| 更多前端组件能力 | keyed renderer registry + `extension.field-renderer`、`extension.record-action` | 不动态 import 插件代码，不做组件市场 | P06 | 新 renderer 只注册映射，不修改通用列表/表单组件 |
| Level 2 受信代码插件 | manifest 已含 `capabilityLevel`；校验器按能力等级分派 | 不建沙箱、签名、隔离运行时、classloader | P07 | ADR-0002 复审条件满足（已有签名与隔离方案） |
| 新 AI 模型/供应商 | `ModelPort` 接口 + HTTP 实现 + fixture 实现 | 不建多供应商路由框架，不预先抽象提示词体系 | P11 | 新供应商只加 adapter，不改 Issue/规格/生成器主流程 |
| 更强的数据存储（未来动态表） | 所有 schema 变更必须走迁移服务；`service.data-access` 是唯一数据入口 | 不让用户输入直接 `CREATE TABLE`；不预建动态表引擎 | P01/P05 | 受控数据表不满足性能需求时，换实现、保端口 |
| 轻量工作流（P13 可选） | `event.domain` 事件发布 + `extension.record-action` 白名单动作 | 不预建通用 BPMN、分支/会签引擎 | P02/P05 | 先用四节点动作链验证，再决定是否演进 |
| 多租户 SaaS | 审计与数据访问都经过 service seam；REST 已 `/api/v1` 版本化 | 不预建 `tenant_id` 列、租户表、租户过滤器 | — | 真实租户需求出现后：迁移加列 + 在 data-access/audit seam 实现租户范围 |
| 微服务拆分 | 模块边界与依赖矩阵（§5）、Service seam | 不预建消息队列、远程调用、分布式事务 | P01 | 团队规模或独立部署需求出现，按既有模块边界拆 |
| 插件市场/远程发布 | 插件版本、content hash、依赖表按不可变包建模 | 不建公网注册、签名分发、计费 | P07 | 答辩后评估 |

规则：**seam 优先，投机禁止**。任何新增预留必须能在这张表或 ADR 中找到理由；"以后可能用到"不能作为建接口、加列、加开关的理由。

## 4. 契约与版本演化

1. REST API 一律挂 `/api/v1/`；破坏性 API 变更提升主版本。响应统一 `{code, message, requestId, details?}`；客户端必须容忍未知错误码。
2. 稳定错误码只增、不改名、不删；删除或改名是 breaking，需要 ADR。基线错误码见 `docs/08-implementation-blueprint.md` §7。
3. `plugin.json.schemaVersion` 与 `RequirementSpec.schemaVersion` 必须随资源保存并可审计；平台只接受已登记版本。MVP 只实现 v1 reader，未知版本返回 `unsupported_schema_version`；新增可选字段为 additive；破坏性变更必须新版本号，并保留旧 reader 至少一个小版本周期。
4. 数据库结构只经编号迁移变更；迁移必须幂等或明确不可逆，破坏性迁移需要回滚说明（沿用 `docs/repository-maintenance.md`）。
5. 扩展点契约变更遵循 `docs/extension-points.md` 的 additive/breaking 规则。

## 5. 模块化与依赖治理

模块清单与职责沿用 `docs/08-implementation-blueprint.md` §2。补充硬规则：

1. `flexforge-common`、`flexforge-runtime` 是基础设施，不依赖业务模块。
2. 业务模块之间禁止依赖对方的 `infrastructure` 包；只允许依赖公开 `api`、application service 接口、domain port 或领域事件。
3. Controller 不直接访问 Repository；插件不依赖 Controller。
4. ArchUnit / import 规则必须覆盖：无循环依赖、无跨模块 `*.infrastructure` 引用、`@PublicApi` 之外的类不得被跨模块引用。
5. 新增后端模块必须先在 `docs/extension-points.md` 或 `docs/08-implementation-blueprint.md` 登记职责与边界，并在同一 PR 提供依赖边界测试；不满足"一个模块一个职责"的拆分请求应被拒绝。
6. 每个跨模块公开包的 `package-info.java` 写一段契约说明（≤ 20 行）：提供什么、不提供什么、变更走什么流程。
7. `flexforge-*` 平台模块只承载骨架能力；任何业务模块（含演示库存）必须位于 `plugins/` 并以 Level 1+ 插件交付，Level 0 仅限平台内核（ADR-0004）。

## 6. 质量门禁（每个阶段的通用出口条件）

1. 格式、lint、type-check、单元/集成测试、依赖边界测试与治理自检（R-GOV）全部通过（分层与 R-GOV 清单见 `docs/11-regression-test-plan.md`；具体命令由 P01 固化）。
2. `docs/coding-standards.md` §7 的硬上限零违规；目标值违规必须有评审说明。
3. 本阶段新增能力要么复用登记册中既有扩展点，要么已完成 proposed → active 登记闭环。
4. 无新增 TODO/FIXME（或全部携带 Issue 号）；无死代码；无未登记扩展点。
5. `docs/`、`STATUS.md`、`docs/project-status.json` 与实现一致。

## 7. 例外与复议

- 文件长度豁免按 `docs/coding-standards.md` §7 执行；复杂度、参数、嵌套不允许豁免。
- 需要违反 R1-R9 的，先写 ADR 说明理由、替代方案和复审条件；禁止"先违规后补票"。
- 用户明确改变约束时：更新唯一来源文档 + `STATUS.md` 进度日志；架构级变更补写或修订 ADR；未经确认的推测不写入基线（与 R7、`AGENTS.md` §4.14 联动）。
- 每个里程碑节点（M2/M4/M6/M8）复盘一次本基线：哪些限制过松、哪些过紧、哪些 seam 已兑现，用 ADR 修正，不用口口相传。

## 8. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-08-20 | v1.0 基线冻结；与 ADR-0003 同时生效 |
