# FlexForge Agent 上下文入口（每轮会话开始时自动注入）

> 本文是协作 Agent 的第一入口。**开始任何任务**：① 读 `STATUS.md` 锚点块定位当前阶段；② 按 §3 路由表只读任务所需文档；③ 全程遵守 §4 每轮约束。**不要通读仓库**。

## 1. 项目一句话

FlexForge：毕业设计"模块化数据管理系统"。主线 = 元数据驱动动态 CRUD + 声明式插件运行时 + Issue/AI 规格到插件骨架。形态 = 模块化单体（Vue 3 + TypeScript / Spring Boot 3 / PostgreSQL）。MVP 边界与非目标见 `README.md`。

## 2. 先定位阶段（每轮第一步）

1. 读 `STATUS.md` 的 `FLEXFORGE_STATUS:BEGIN..END` 块：`CURRENT_STAGE_ID`、`STAGE_STATUS`、`NEXT_ACTION`、`EXIT_GATE`、`BLOCKERS` 是唯一进度锚点。
2. `docs/project-status.json` 是机器镜像；改进度必须两处同步，否则 R-GOV-04 回归测试失败。
3. 各阶段的日历目标与裁剪触发日期以 `docs/01-project-plan.md` §2.1 倒排表为准（答辩 2027-05 中旬、中期检查 2027-01）；进度落后触发裁剪时按 §5 处理。
4. 任务对应 P 阶段时，只读 `docs/09-detailed-implementation-plan.md` 的"阶段通用质量门槛" + 该阶段小节；完成标准以该节验收标准为准。

## 3. 文档路由表（按任务读最少文档）

| 任务 | 必读 | 按需 |
| --- | --- | --- |
| 任何任务 | `STATUS.md` + 本文 | — |
| 定位文件/模块/项目结构 | `docs/project-index.md`（`grep` 对应节） | — |
| 后端代码 | `docs/coding-standards.md`、`docs/10-engineering-governance.md`、`docs/03-architecture.md`、`docs/08-implementation-blueprint.md`、`docs/02-requirements.md`（找 FR/NFR 编号） | 对应 P 节、相关 `docs/adr/` |
| 前端代码 | 同上 + `docs/08` §6 | `docs/06-dsh-reference-study.md` §2.7 |
| 数据库/迁移 | `docs/repository-maintenance.md` §5、`docs/07-plugin-runtime-data-model.md` | 对应 P 节 |
| 安全相关改动（认证/权限/文件上传接口/AI/密钥管理/CORS） | `docs/13-security-baseline.md` | 对应 P 节、`docs/04` §4 |
| 插件/扩展点/PluginRuntime | `docs/extension-points.md`、`docs/adr/0002`、`docs/07`、`docs/08` §3-4 | `docs/06-dsh-reference-study.md` |
| Issue/AI/生成器 | `docs/09` P10-P12、`docs/03` §6、`docs/02` FR-ISSUE-* | `docs/adr/0002` |
| 测试/回归/CI | `docs/04-test-strategy.md`、`docs/11-regression-test-plan.md` | `docs/repository-maintenance.md` §3 |
| 改文档/状态 | `docs/05-documentation-guide.md`、`STATUS.md` | `docs/repository-maintenance.md` §1-2、§8 |
| 新模块/架构决策/破坏性变更 | `docs/adr/`（含 0003 复审条件、0004 骨架与业务边界、0005 迁移机制）、`docs/10` §5 | `docs/00-feasibility-review.md` |
| 原始设想/论文背景 | `FlexForge.md`（历史稿；与 MVP 冲突时以 `docs/` 为准） | — |

阅读规则：

- 文档内按 § 引用时，用 `grep` 只读该节，不整篇通读。
- 先 `grep` 需求编号/类名/表名，再打开对应行段。
- 同一信息多处出现时以"必读"列为准；与 `FlexForge.md` 冲突时以 `docs/` 最新决策为准。

## 4. 每轮必须遵守的持久化约束（无例外）

1. 先读 §2/§3 文档再动手，不得凭记忆改。
2. 不扩大 MVP，不把可选功能变成默认交付承诺（非目标见 `README.md`）。
3. 不执行破坏性数据库操作、删除用户数据或生产发布。
4. 禁止直接执行任意 shell、SQL、浏览器脚本或生产发布命令（允许跑项目已固化的检查/测试脚本）。
5. 不安装未经说明的新依赖；只改任务相关文件，不碰锁文件以外的无关文件。
6. AI 输出只是草稿：必须经过 Schema 校验、人工审阅和自动化测试后才能算完成。
7. 生成插件只能用声明式资源 + `docs/extension-points.md` 已登记扩展点。
8. 代码满足 `docs/coding-standards.md` §7 数值硬约束与 `docs/10-engineering-governance.md` 依赖方向；新扩展点先登记、先有消费方、先有注册/撤销测试。
9. 任何权限、动态 SQL、插件安装、状态迁移改动都必须有失败路径测试，且满足 `docs/13-security-baseline.md` 的 S1-S9 安全红线。
10. 需求-实现-测试-文档必须用 FR/NFR 编号关联；变更摘要记录未完成项、假设和下一步。
11. 进度/状态变更：先更新 `STATUS.md`，再更新计划和代码；`docs/project-status.json` 同步；阶段切换前先跑该阶段退出条件与 L3 阶段回归；阻塞与问题处理按 §5。
12. 不提交密钥、真实用户数据、未脱敏日志、未经人工审核的 AI 产物；不读取、展示或向任何外部服务发送密钥与凭据（secret 只经环境变量引用，不在会话中回显其值），对外发送任何未脱敏内容前必须逐次征得用户确认。
13. 无法验证的结论必须标注"假设"，禁止伪装成已完成事实。
14. 约束即时同步：当用户在会话中**明确**更改需求、边界、流程或约束时，必须在同一轮按 §3 路由表找到唯一归属文档并完成最小更新（引用决策内容，可更新本文 AGENTS.md），同步 `STATUS.md` 进度日志，并按 §6 提交推送；架构/契约级变更先写或修订 ADR。未经用户确认的推测、口头暂定意见不得写入约束文档。
15. 索引与结构分离：项目结构、文件级索引只维护在 `docs/project-index.md`；结构或文档变更时同一轮更新该索引并写变更记录。本文件不保存结构树、只保存指针，以保持稳定与 token 缓存命中率。

## 5. 阻塞与问题处理流程（发现问题先走这里）

**第 1 步：分类，禁止带病硬写**

| 类型 | 定义 | 处置 |
| --- | --- | --- |
| 阻塞 | 当前任务/阶段无法继续：依赖缺失、环境启动失败、权限不足、迁移冲突、缺少必要决策 | 先走第 2 步记录，再按第 3 步分叉 |
| 缺陷 | 已有成果不满足验收或回归失败 | 按 `docs/04-test-strategy.md` 缺陷等级定 P0-P3；P0/P1 当前阶段内修复，P2/P3 开 Issue 排期 |
| 疑问 | 信息不足但可以继续 | 显式标注"假设"继续，并写入变更摘要 |
| 治理违规 | 未登记扩展点、突破硬上限、R-GOV 失败、门禁被绕过 | 按 P0 处理，不得提交 |

**第 2 步：记录证据与状态**

1. 先复现并留证：命令、输出、文件路径/行号、失败阶段、commit hash。
2. `STATUS.md`：`BLOCKERS` 用 `问题 | 影响阶段 | 复查时间` 逐条写（多条用分号分隔），禁止只写"阻塞"；进度日志同步记一条。
3. `docs/project-status.json` 的 `blockers` 数组同步（R-GOV-04 校验，不一致 CI 失败）。
4. 涉及需求/契约的问题开 GitHub Issue，代码与文档引用 Issue 号。

**第 3 步：决策分叉**

- 可自主修复的缺陷：最小修复 → 跑 L1/L2 匹配回归 → 同步文档与状态；不顺手改无关内容。
- 需要人工拍板（范围、权限、架构、外部依赖、破坏性操作）：停下来，给出 ≤3 个选项 + 推荐 + 影响；等待期间不写依赖该决策的代码。
- 不可解阻塞：保持 `BLOCKERS` 记录待复查；不通过放宽门禁、删测试、改 fixture、扩大范围或重复尝试被拒操作来绕过。

**第 4 步：解除**

- 修复验证通过后：清除对应 `BLOCKERS` 条目并同步 JSON；进度日志记录解决方案与证据；开过 Issue 的链接修复 commit 并更新 Issue 状态。

## 6. Git 工作流（提交/分支/PR/Issue 速查）

详细规范见 `docs/repository-maintenance.md` §2/§8，这里只给决策表：

| 场景 | 动作 |
| --- | --- |
| 文档/状态/纯文本，不改契约 | 直推 `main`（先跑文档检查） |
| 单文件行为修复，L1/L2 相关回归通过 | 直推 `main`（改动 ≤ 1 个文件） |
| 新功能/重构/迁移/契约/依赖/多文件/阶段交付/试验 | 建分支 `feat\|fix\|refactor\|docs\|chore/<name>` → 小步提交 → push → 开 PR |
| 核心流程或风险改动 | 必须 PR 合并；单人项目执行下方"单人审查流程"替代团队人工审查 |
| P0/P1 缺陷 | 立即修复（`fix/` 分支或单文件直推），不靠只开 Issue 挂起 |
| P2/P3、暂留想法、技术债、TODO | 先开 Issue（暂留问题写"原因 + 复查触发条件"），代码注释引用 Issue 号 |
| 阻塞/需人工决策 | 按 §5 记 BLOCKERS，并同步开 Issue 双记 |

单人审查流程（个人毕业设计，无团队审查条件）：① PR 描述附按 `docs/coding-standards.md` §6 清单逐项自查的结论；② 合并前交叉审查必须由**独立子代理**执行（在主会话外启动全新 Agent 做缺陷优先审计，主会话不得自审自己写的代码）；审查结论与逐条回应记录到 PR 评论；③ M2/M4/M6/M8 里程碑请导师或同学对核心模块抽样复核，复核结论写入进度日志。

提交与推送规则：

- 一个提交一个主题；复杂改动拆成小步提交，**每个提交后仓库仍可构建**；禁止"最后一步才可编译"的一揽子提交。
- 建议顺序：文档/契约 → 迁移/核心 → API/UI → 测试与状态；新增依赖单独提交。
- 提交信息 `<type>(<scope>): <summary>`。
- 每完成一个任务或每次会话结束前 push；禁止 force push `main`。
- PR 默认 merge commit 保留分批历史；仅单琐碎提交可 squash。

## 7. 索引与项目结构（按需读，不在本文件维护）

- 文件级索引唯一位置：`docs/project-index.md`（项目结构、文档地图、模块→文档映射、被阻断速查、自更新规则）。
- 需要定位文件/模块时先 `grep` 该索引对应节，不要通读。
- 结构/目录/文档/模块变化时，按 §4.15 立即更新索引并写变更记录；本文件不复制结构。

## 8. Token 经济

- 只读路由表"必读"列；`grep` 章节代替整篇读取；`docs/09` 只读当前阶段小节。
- 文件级定位只读 `docs/project-index.md`，不复制其内容到本文件。
- `FlexForge.md`、`docs/06`、ADR 只在路由表指向时读。
- 回复引用 `文件路径 + 章节/行号`，不复制大段原文。
