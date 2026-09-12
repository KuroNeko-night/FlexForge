# 仓库维护约束

## 1. 目录约定

```text
backend/                 后端源码
frontend/                前端源码
plugins/                 示例和可安装插件
database/migrations/     数据库迁移
database/seed/           演示数据
docs/                    项目文档、ADR、测试记录
scripts/                 可重复执行的开发脚本
tests/                   跨模块和端到端测试
```

目录可随实现调整，但变更必须同步 README 和相关文档。不要把临时导出物、模型原始响应、个人笔记和 IDE 配置提交到仓库。

文件级索引见 `docs/project-index.md`（结构、文档地图、模块→文档映射）；目录/模块/长期文档变化时必须同步更新该索引并写变更记录（其 §7）。

## 2. 分支、提交与推送

`main` 始终保持可构建、可演示。任何提交前先跑 L1 冒烟（`docs/11-regression-test-plan.md` §2）；文档/状态类变更至少跑文档检查。

### 2.1 提交粒度（分批提交）

1. 一个提交只解决一个主题；格式化、重命名与业务改动不得混提交。
2. 复杂改动必须拆成可独立审查的提交序列，每个提交之后仓库仍可构建/可运行；禁止"只在最后一个提交才可编译"的一揽子提交。
3. 建议顺序：文档/契约 -> 迁移/核心 -> API/UI -> 测试与状态同步；数据库迁移放在依赖它的代码之前。
4. 新增依赖单独提交，并说明用途、许可证与替代方案。
5. 改动难以拆小 = 任务本身太大：先拆任务再写提交，不允许用大提交掩盖边界不清。

### 2.2 直推 main 的边界（无需分支/PR）

同时满足以下条件的才能直接提交并推送 `main`：

- 仅文档/状态/纯文本：`docs/`、`README.md`、`STATUS.md`、`docs/project-status.json` 等，且不改变任何契约；
- 或单文件行为修复：改动 ≤ 1 个文件，且 L1/L2 相关回归已通过；
- 提交前通过对应检查。

### 2.3 必须创建分支 + PR 的场景

以下情况必须创建分支，并在合并前开 PR：

- 任何行为变更、新功能、重构、破坏性变更；
- 数据库迁移、公开契约、插件格式、依赖版本变更；
- 跨多文件改动、阶段交付、试验性工作。

分支命名：功能 `feat/<short-name>`、修复 `fix/<short-name>`、重构 `refactor/<short-name>`、文档系列 `docs/<short-name>`、杂项 `chore/<short-name>`。

PR 要求：

- 描述包含：背景、需求编号、实现摘要、测试结果、数据库/配置变更和回滚方式。
- AI 生成代码必须人工阅读、修改和测试；核心流程变更执行单人审查流程：PR 描述附 `docs/coding-standards.md` §6 自查结论，合并前由**独立子代理**交叉审查（主会话外启动全新 Agent 做缺陷优先审计，主会话不得自审自己写的代码）并逐条回应，结论记录到 PR 评论；M2/M4/M6/M8 里程碑请导师或同学对核心模块抽样复核并记录到进度日志（个人毕业设计无团队审查条件，此流程替代"人工审查"）。
- CI 与文档检查全绿后才能合并。
- 默认 merge commit（`--no-ff`），保留分支内分批提交历史；仅"单琐碎提交"分支允许 squash。
- 禁止向 `main` 及共享分支 force push。

### 2.4 推送节奏

- 每完成一个可验证任务，或每次会话结束前，必须 push 到 origin（分支或 main），不留长期未推送工作。
- push 前确认远端变化，用 rebase/merge 整合，不覆盖他人提交。

## 3. 质量门禁（CI 阻断项）

CI 入口：`.github/workflows/ci.yml`（push `main` 与 PR 触发）；本地入口：`node scripts/check-repo-health.mjs`。backend/frontend/docker/依赖审计任务在对应文件（构建文件、`package.json`、`Dockerfile`、lockfile）出现后自动激活；密钥扫描（gitleaks）全历史生效。

以下检查在 CI 中执行，任一失败即阻断合并：

1. 格式检查（Prettier / Spotless 或等价工具）。
2. 数值硬门禁：文件长度、函数长度、圈复杂度、参数个数、嵌套深度按 `docs/coding-standards.md` §7 执行；硬上限 = error，目标值 = warn。
3. 依赖边界：ArchUnit/import 规则覆盖无循环依赖、无跨模块 `infrastructure` 引用、未标注 `@PublicApi`/`@ExperimentalApi` 的类不得被跨模块引用。
4. 回归冒烟与治理自检：`scripts/check-repo-health` 至少覆盖 `docs/11-regression-test-plan.md` §3 的 R-GOV-01..06；违规样例（fail-open guard）必须按预期失败，若意外通过视为 P0。
5. 单元、集成与启动冒烟测试。
6. 文档检查：Markdown 相对链接有效、`docs/project-status.json` 可解析且与 `STATUS.md` 锚点一致。

新增扩展点、服务 seam 或公开契约前，必须先完成 `docs/extension-points.md` 登记，否则评审不通过。

## 4. 受保护内容

以下内容不得直接提交：

- `.env`、API 密钥、JWT 密钥、数据库真实密码。
- 生产数据库导出、用户隐私和未脱敏日志。
- 由 IDE 或构建工具生成的缓存、依赖目录和临时文件。
- 未经过人工审核的 AI 生成代码或插件包。

## 5. 数据库和插件约束

- 数据库结构只能通过编号迁移变更；根 `database/migrations/` 只允许平台骨架迁移（`sys_*`/`meta_*`/`data_*`/`plugin_*`/`issue_*` 及 `issue`/`requirement_spec`，`data_*` 为动态记录存储 2026-08-24 P05 定案；`issue*`/`requirement_spec` 为 P10 Issue 域平台表，`ai_*` 为 P11 AI 任务记录表，`processor_*` 为 P23 处理器产物表，`kb_*` 为 P28 知识库与助手会话表），业务表迁移必须位于插件包内并由插件安装流程执行（ADR-0004）。
- 迁移必须幂等或明确记录不可逆操作；破坏性变更需要备份和回滚说明。
- 插件必须经过 manifest Schema、依赖、路径和资源大小校验。
- 插件必须声明能力等级；Level 1 只能引用白名单扩展点和 renderer ID，禁止提交任意可执行源码。
- 插件版本包不可变；安装实例、版本和激活尝试分开记录。
- 插件安装、启停和卸载必须可审计；默认停用而不是删除业务数据。
- 每个注册项必须绑定 activationId 并可通过 disposer 撤销，避免停用后残留菜单、权限、监听器或定时任务。

## 6. 依赖与版本

- 锁定 JDK、Node、包管理器、数据库和 Docker 基础镜像的大版本。
- 新增依赖需要说明用途、许可证、维护活跃度和替代方案。
- 每次升级基础依赖先在独立分支运行完整测试和启动检查。

## 7. 发布与备份

- 发布前生成版本号、变更摘要、数据库迁移清单和回滚步骤。
- 演示环境使用种子数据，不使用真实用户数据。
- 每个里程碑保存可复现的部署包或镜像标签。

## 8. Issue 与暂留问题

以下情况必须开 GitHub Issue：

1. P2/P3 缺陷、已知限制（P0/P1 直接修复，不允许只记 Issue 挂起）。
2. 本阶段决定不做、但值得留档的想法或可选方向（标签 `backlog`）。
3. 技术债与豁免：SIZE-WAIVER 累计、flaky 隔离、TODO/FIXME（代码注释必须携带 Issue 号，与 `docs/10-engineering-governance.md` R8 联动）。
4. 需要人工决策或等待中的阻塞（与 `AGENTS.md` §5 联动：STATUS 的 BLOCKERS 与 Issue 双记）。

不开 Issue 的情况：当前任务内几分钟可修的小问题、纯错别字/格式、已被需求文档覆盖的内容。

Issue 最小内容：标题；现象/背景；影响（阶段 + FR/NFR 编号）；证据或复现步骤；建议方向；优先级（P0-P3）与标签。暂留问题必须写明"暂留原因 + 复查触发条件"，禁止无限期挂起；P13 开始前与 P14 交付前各清理一次 backlog，逐条注明"已实现 / 已关闭 / 仍保留及理由"。

## 9. 开发环境与运行基线

- **工具链锁定**：JDK 17（编译目标 `--release 17`；本机可用 JDK 21 LTS）、Maven 3.9.16（Wrapper 锁定，无需本机安装）、Spring Boot 4.0.7、Node.js LTS、PostgreSQL 17（`postgres:17-alpine`）、Docker Compose；依赖仓库与镜像：Maven 走 `.mvn/settings.xml` 阿里云镜像，Docker 拉取依赖 daemon `registry-mirrors`（本机已配置）。禁止"最新版"这类浮动版本。
- **Windows-first**：开发机为 Windows，所有脚本必须可在 PowerShell/pwsh 下执行；脚本读取 `docs/project-status.json` 等 UTF-8 文件必须显式按 UTF-8 读取；仓库统一 LF（`.editorconfig` + `.gitattributes` 已固定）。
- **密钥纪律**：密钥只经环境变量或 `.env`（已 gitignore）注入；仓库只允许提交 `.env.example`（占位值）；配置文件不得出现真实密钥。
- **测试数据库隔离**：集成/E2E 测试只允许连接一次性 schema 或临时容器（Testcontainers/CI service），禁止指向开发库、演示库或任何有数据的库；破坏性测试只能跑在隔离 fixture 上。
- **CI 边界**：GitHub Actions 只运行构建、测试、lint、回归与文档检查；不部署、不发布、不访问生产环境。
- **仓库体积**：单文件超过 1 MB 不提交（插件包 fixture 使用最小样例）；大附件走 Release 或本地归档。
- **时间口径**：数据库统一 UTC（`timestamptz`），前端本地化显示，见 `docs/coding-standards.md` §5。
