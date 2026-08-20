# 文档维护指南

## 1. 文档分工

- `README.md`：项目入口、当前状态、快速导航，保持短小。
- `FlexForge.md`：早期完整概念设计，保留历史上下文；若与 MVP 文档冲突，以 `docs/` 下的最新决策为准。
- `docs/00-feasibility-review.md`：范围和风险基线。
- `docs/01-project-plan.md`：里程碑、交付物和变更控制。
- `docs/02-requirements.md`：可验收需求与用户场景。
- `docs/03-architecture.md`：架构边界和关键技术决策。
- `docs/06-dsh-reference-study.md`：外部参考实现的学习记录和取舍。
- `docs/07-plugin-runtime-data-model.md`：插件运行时持久化对象、状态和测试门槛。
- `docs/08-implementation-blueprint.md`：可编码的模块、接口、数据流和生命周期蓝图。
- `docs/09-detailed-implementation-plan.md`：P00-P14 详细实施阶段和验收标准。
- `docs/10-engineering-governance.md`：工程质量与可扩展性治理基线（反屎山红线、seam 预留清单、契约演化、质量门禁）。
- `docs/11-regression-test-plan.md`：回归分层、治理自检（R-GOV）与按阶段交付的回归包。
- `docs/extension-points.md`：扩展点唯一登记册；任何新扩展点先在此登记。
- `docs/project-index.md`：文件级索引（项目结构、文档地图、模块→文档映射、被阻断速查）；结构变化即时更新并写变更记录（自更新规则见该文 §7）。
- `STATUS.md`：开发者查看和更新当前进度的唯一可见锚点。
- `docs/project-status.json`：与 `STATUS.md` 同步的机器可读状态镜像。
- `docs/adr/`：不可逆或影响面较大的架构决策。
- `docs/` 下的测试、部署、排障文档：以实际实现为准，禁止只写理想状态。

## 2. 更新规则

代码行为、接口、数据结构或插件格式发生变化时，同一提交必须同步更新受影响文档。文档中的状态、版本、日期使用明确值，不使用“最近”“稍后”等模糊表述。

需求变更先改需求文档和计划，再改代码；紧急修复可以先改代码，但必须在同一迭代补齐文档和测试。

约束与治理文档即时同步：用户明确更改需求、边界或流程后，由 Agent 在同一轮更新唯一归属文档（`coding-standards.md`、`docs/10-engineering-governance.md`、`docs/extension-points.md`、`docs/repository-maintenance.md`、`AGENTS.md` 等），并同步 `STATUS.md` 进度日志；架构/契约级变更先写或修订 ADR。未经用户确认的推测不得写入权威文档。

## 3. 文档格式

- 使用 Markdown，标题层级从 `#` 开始递增。
- 表格用于稳定字段和对比信息，流程优先使用编号列表或 Mermaid。
- 每份长期文档包含版本、状态和更新日期。
- 示例配置必须可复制运行或明确标注为伪代码。
- 不在文档中提交真实密钥、个人信息、生产地址和未脱敏日志。

## 4. 评审清单

- 是否说明了目标读者和适用范围？
- 是否与当前实现、需求编号和接口名称一致？
- 是否包含失败路径、权限边界和验收方式？
- 是否给出迁移、回滚或兼容策略？
- 是否能让没有上下文的人按文档完成操作？
