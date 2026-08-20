# ADR-0003：建立工程质量与扩展治理基线

**状态**：Accepted
**日期**：2026-08-20

## 背景

P00 已冻结 MVP 范围与总体架构，但存在两个即将被代码放大的缺口：

1. "代码可读性、模块化"只在 `docs/coding-standards.md` 中是定性要求，没有可被 CI 执行的数值门禁；P01 即将开始写代码，这是零迁移成本的最后窗口。
2. 扩展点 ID 在 `docs/03-architecture.md` 与 `docs/08-implementation-blueprint.md` 中命名不一致（`extension.entity-renderer` / `field.renderer` / `navigation.item`），继续下去会出现"同一能力两套名字"的屎山源头。

## 决策

1. 采用数值化代码门禁，唯一事实来源为 `docs/coding-standards.md` §7：后端 Java 文件硬上限 400 行、Vue SFC 350 行、TS/JS 300 行、SQL 迁移 300 行，函数长度/圈复杂度/参数/嵌套均设硬上限；CI 对硬上限报 error，对建议目标报 warn。复杂度、参数、嵌套不允许豁免，文件长度豁免需登记与定期复议。
2. 建立 `docs/extension-points.md` 作为扩展点唯一登记册，统一扩展点 ID 为 `service.meta`、`service.data-access`、`service.audit`、`extension.navigation`、`extension.field-renderer`、`extension.record-action`、`event.domain`；执行"先登记后使用、必须有消费方、注册即可撤销、breaking 必须 ADR"。
3. 可扩展性采用"seam 预留、投机禁止"策略：现在只建接口、注册表、能力等级校验位、`/api/v1` 版本前缀与 feature flag 开关；不预建多租户字段、通用 BPMN、Level 2 沙箱、消息队列或第二套数据路径。未来演进触发条件写入 `docs/10-engineering-governance.md` §3。
4. 跨模块公开接口必须标注 `@PublicApi` / `@ExperimentalApi`；未标注视为内部实现。模块依赖方向与无循环依赖由 ArchUnit/import 规则在 CI 强制。

## 影响

### 正面影响

- 从第一个提交起就有可机器验证的复杂度与依赖边界，避免后期"存量债无法清"。
- 扩展点命名与契约单一来源，插件、前端、后端不会各自发明名字。
- 未来能力（多字段类型、新 renderer、新模型供应商、多租户、服务拆分）有明确的接入 seam 与触发条件，不需要推倒重来。

### 负面影响

- 数值门禁可能在局部强迫拆文件/拆函数，短期多花少量时间；目标值设为 warn 保留弹性。
- 扩展点登记流程增加少量评审开销。
- "预留 seam"策略意味着部分未来能力仍需重新设计内部实现，只是保住对外契约与数据迁移路径。

## 备选方案

1. **只写规范、不配 CI**：不采用。无机器检查的规范会迅速失效，无法阻止屎山。
2. **更严格的限制（如文件 ≤ 200 行）**：不采用。毕业设计节奏下会产生大量无意义拆分为代价。
3. **不设登记册，沿用现有文档**：不采用。03/08 已出现 ID 不一致，继续散落会加剧契约漂移。

## 复审条件

- 数值门禁连续两个里程碑产生大量豁免或强拆时，调整阈值。
- 出现登记册无法表达的扩展需求（如跨进程扩展、远程插件）时，复审扩展点模型。
- 项目进入多人协作或服务拆分评估时，复审依赖矩阵与 `@PublicApi` 标注机制。

## 基线同步

同一变更已同步：`docs/coding-standards.md`（数值门禁）、`docs/extension-points.md`（登记册）、`docs/10-engineering-governance.md`（治理基线）、`docs/03`/`docs/08`（ID 统一与交叉引用）、`docs/09`（阶段通用质量门槛）、`docs/02`（NFR-MAINT-01/02）、`docs/04`（静态门禁测试）、`docs/05`/`README.md`（文档索引）、`AGENTS.md`（Agent 约束）、`STATUS.md`/`docs/project-status.json`（状态镜像）。

2026-08-20 补充：`docs/11-regression-test-plan.md`（治理自检 R-GOV 与分层回归，验证门禁本身不漂移）与 `AGENTS.md` 重写（Agent 上下文入口：阶段定位、文档路由表、每轮持久约束、token 经济）。
