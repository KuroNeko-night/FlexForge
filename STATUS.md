# FlexForge 开发状态

> 这是项目当前进度的**唯一可见锚点**。开发者开始工作前先看这里，阶段切换时必须先更新这里，再更新计划和代码。

<!-- FLEXFORGE_STATUS:BEGIN -->
CURRENT_STAGE_ID: P01
CURRENT_STAGE_NAME: 仓库与工程骨架
STAGE_STATUS: ready_to_start
PROJECT_PROGRESS: 5%
LAST_UPDATED: 2026-08-20
OWNER: project-maintainer
NEXT_ACTION: 初始化 backend、frontend、database、plugins、tests、scripts 目录和最小启动骨架，接入 Checkstyle/ESLint/ArchUnit 等质量门禁工具链与最小回归测试骨架（治理自检 + 冒烟）
EXIT_GATE: 新环境可按 README 启动前后端与 PostgreSQL，健康检查和 V001 迁移通过
BLOCKERS: none
<!-- FLEXFORGE_STATUS:END -->

## 阶段看板

| 阶段 | 名称 | 状态 | 退出条件 |
| --- | --- | --- | --- |
| P00 | 设计基线冻结 | completed | 文档、MVP 边界和 ADR 已冻结 |
| P01 | 仓库与工程骨架 | ready_to_start | 新环境可启动，健康检查和初始迁移通过 |
| P02 | 核心契约与可观测性 | pending | 注册/撤销、统一错误和审计端口通过测试 |
| P03 | 认证、RBAC 与系统壳 | pending | 三类角色和 JWT 测试通过 |
| P04 | 元数据写模型 | pending | 实体/字段/视图配置 API 通过验收 |
| P05 | 动态数据运行时 | pending | 动态实体完成 CRUD 和字段校验 |
| P06 | 前端动态渲染 | pending | 无业务页面代码即可显示动态实体 |
| P07 | 插件包校验与版本存储 | pending | 合法包可预览，非法包被拒绝 |
| P08 | PluginRuntime 生命周期 | pending | 激活、停用、回滚、stale 拒绝通过 |
| P09 | 库存示例插件 | pending | 库存插件可安装并完成演示 |
| P10 | Issue 与规格 Schema | pending | Issue 状态机和规格版本可审计 |
| P11 | AI 适配器与生成器 | pending | 在线/fixture/手工三条路径可用 |
| P12 | Agent Issue 端到端闭环 | pending | 干净数据库连续三次完成主流程 |
| P13 | 加分项与体验优化 | optional | 不影响主线稳定性 |
| P14 | 质量收敛与答辩交付 | pending | 全量验收通过，进入 release candidate |

## 更新规则

1. 开始阶段：将该阶段 `STAGE_STATUS` 改为 `in_progress`，填写 `NEXT_ACTION`。
2. 每完成一个可验证任务：更新阶段看板、`PROJECT_PROGRESS` 和下方进度日志。
3. 遇到阻塞：`BLOCKERS` 按 `问题 | 影响阶段 | 复查时间` 逐条填写（多条用分号分隔），禁止只写“阻塞”；同时在进度日志记录一条，并同步 `docs/project-status.json` 的 `blockers` 数组。解决后记录解决方案与证据，再清除该条。完整流程见 `AGENTS.md` §5。
4. 达到退出条件：将阶段改为 `completed`，填写验收证据路径，并把下一阶段设为 `ready_to_start`。
5. 代码、测试、文档或数据库发生行为变化时，`LAST_UPDATED` 必须同步更新。
6. `docs/project-status.json` 是给脚本和后续仪表盘读取的镜像，必须与本文件的状态锚点保持一致。

## 进度日志

| 日期 | 阶段 | 变更 | 验收证据 |
| --- | --- | --- | --- |
| 2026-08-20 | P00 | 完成可行性、架构、需求、仓库规范和 dsh 参考研究；新增详细实施路线 | `docs/00-09`、`docs/adr/0001-0002` |
| 2026-08-20 | P01 | 冻结工程质量与扩展治理基线：数值门禁、扩展点登记册、seam 预留清单；P01 任务补充质量工具链 | `docs/10`、`docs/extension-points.md`、`docs/adr/0003` |
| 2026-08-20 | P01 | 新增回归测试计划（R-GOV 治理自检 + 分层回归）；重写 AGENTS.md 为 Agent 上下文入口/文档路由表 | `docs/11`、`AGENTS.md` |
| 2026-08-20 | P01 | 冻结"系统骨架不含业务"边界：演示库存与第三方插件同权、可停用/卸载；新增 ADR-0004 与骨架纯净性回归（R-GOV-09） | `docs/adr/0004`、`docs/03` §3.5、`docs/11` |
| 2026-08-20 | P01 | 建立 GitHub 私有仓库（main 分支）并推送初始基线，origin 已绑定，等待项目启动 | `https://github.com/KuroNeko-night/FlexForge` |
| 2026-08-20 | P01 | 明确阻塞与问题处理流程（分类→记录→决策分叉→解除）写入 AGENTS.md §5；STATUS 更新规则与 R-GOV-04 同步覆盖 blockers 镜像 | `AGENTS.md` §5、`STATUS.md`、`docs/11` |
| 2026-08-20 | P01 | 完善 Git 流程：分批提交、直推 main 边界、分支/PR 时机、暂留问题 Issue 规则 | `docs/repository-maintenance.md` §2/§8、`AGENTS.md` §6、`CONTRIBUTING.md` |
| 2026-08-20 | P01 | 新增约束即时同步规则：用户明确更改需求/边界/流程时，Agent 同一轮更新唯一归属文档 + STATUS 日志；架构级变更走 ADR；未确认推测不得写入 | `AGENTS.md` §4.14、`docs/05`、`docs/10` §7 |
| 2026-08-20 | P01 | 封堵实施前高概率坑：插件迁移 runner（ADR-0005）、开发环境基线、元数据兼容与删除语义、上传安全、提示词版本化、UTC/错误脱敏 | `docs/adr/0005`、`docs/07/08/09/10`、`docs/repository-maintenance.md` §9、`docs/coding-standards.md` §5 |
| 2026-08-20 | P01 | 成本控制：项目结构/文件级索引迁出 AGENTS.md 至 docs/project-index.md（自更新 + 变更注释），AGENTS 只留指针；R-GOV-05 校验索引完整性 | `docs/project-index.md`、`AGENTS.md` §7/§4.15、`docs/11` |

