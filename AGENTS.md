# FlexForge Agent 上下文入口（每轮会话开始时自动注入）

> 本文是协作 Agent 的第一入口。**开始任何任务**：① 读 `STATUS.md` 锚点块定位当前阶段；② 按 §3 路由表只读任务所需文档；③ 全程遵守 §4 每轮约束。**不要通读仓库**。

## 1. 项目一句话

FlexForge：毕业设计"模块化数据管理系统"。主线 = 元数据驱动动态 CRUD + 声明式插件运行时 + Issue/AI 规格到插件骨架。形态 = 模块化单体（Vue 3 + TypeScript / Spring Boot 3 / PostgreSQL）。MVP 边界与非目标见 `README.md`。

## 2. 先定位阶段（每轮第一步）

1. 读 `STATUS.md` 的 `FLEXFORGE_STATUS:BEGIN..END` 块：`CURRENT_STAGE_ID`、`STAGE_STATUS`、`NEXT_ACTION`、`EXIT_GATE`、`BLOCKERS` 是唯一进度锚点。
2. `docs/project-status.json` 是机器镜像；改进度必须两处同步，否则 R-GOV-04 回归测试失败。
3. 任务对应 P 阶段时，只读 `docs/09-detailed-implementation-plan.md` 的"阶段通用质量门槛" + 该阶段小节；完成标准以该节验收标准为准。

## 3. 文档路由表（按任务读最少文档）

| 任务 | 必读 | 按需 |
| --- | --- | --- |
| 任何任务 | `STATUS.md` + 本文 | — |
| 后端代码 | `docs/coding-standards.md`、`docs/10-engineering-governance.md`、`docs/03-architecture.md`、`docs/08-implementation-blueprint.md`、`docs/02-requirements.md`（找 FR/NFR 编号） | 对应 P 节、相关 `docs/adr/` |
| 前端代码 | 同上 + `docs/08` §6 | `docs/06-dsh-reference-study.md` §2.7 |
| 数据库/迁移 | `docs/repository-maintenance.md` §5、`docs/07-plugin-runtime-data-model.md` | 对应 P 节 |
| 插件/扩展点/PluginRuntime | `docs/extension-points.md`、`docs/adr/0002`、`docs/07`、`docs/08` §3-4 | `docs/06-dsh-reference-study.md` |
| Issue/AI/生成器 | `docs/09` P10-P12、`docs/03` §6、`docs/02` FR-ISSUE-* | `docs/adr/0002` |
| 测试/回归/CI | `docs/04-test-strategy.md`、`docs/11-regression-test-plan.md` | `docs/repository-maintenance.md` §3 |
| 改文档/状态 | `docs/05-documentation-guide.md`、`STATUS.md` | `docs/repository-maintenance.md` §1-2 |
| 新模块/架构决策/破坏性变更 | `docs/adr/`（含 0003 复审条件、0004 骨架与业务边界）、`docs/10` §5 | `docs/00-feasibility-review.md` |
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
9. 任何权限、动态 SQL、插件安装、状态迁移改动都必须有失败路径测试。
10. 需求-实现-测试-文档必须用 FR/NFR 编号关联；变更摘要记录未完成项、假设和下一步。
11. 进度/状态变更：先更新 `STATUS.md`，再更新计划和代码；`docs/project-status.json` 同步；阶段切换前先跑该阶段退出条件与 L3 阶段回归。
12. 不提交密钥、真实用户数据、未脱敏日志、未经人工审核的 AI 产物。
13. 无法验证的结论必须标注"假设"，禁止伪装成已完成事实。

## 5. 项目完整结构

当前（P01 前，文档阶段）：

```text
FlexForge/
├── README.md / FlexForge.md（历史概念稿）/ CONTRIBUTING.md / AGENTS.md
├── STATUS.md                  # 进度锚点
├── docs/
│   ├── 00-feasibility-review.md  01-project-plan.md  02-requirements.md
│   ├── 03-architecture.md  04-test-strategy.md  05-documentation-guide.md
│   ├── 06-dsh-reference-study.md  07-plugin-runtime-data-model.md
│   ├── 08-implementation-blueprint.md  09-detailed-implementation-plan.md
│   ├── 10-engineering-governance.md  11-regression-test-plan.md
│   ├── coding-standards.md  repository-maintenance.md  project-status.json
│   ├── extension-points.md     # 扩展点唯一登记册
│   └── adr/0001-0004
└── .editorconfig / .gitignore
```

目标结构（P01 起，以 `docs/repository-maintenance.md` §1 为准）：

```text
backend/    flexforge-app/common/runtime/auth/system/meta/data/plugin/issue/ai（workflow 可选）
            每个模块内部：api -> application -> domain -> infrastructure
frontend/   Vue 3 + TypeScript（core/components/views/dynamic）
database/   migrations/ + seed/
plugins/    example-inventory（Level 1 声明式示例）
tests/      跨模块/E2E/fixtures/（含 violations 违规样例，见 docs/11 §5）
scripts/    check-repo-health 等可重复脚本
docs/       同上，随实现持续更新
```

## 6. Token 经济

- 只读路由表"必读"列；`grep` 章节代替整篇读取；`docs/09` 只读当前阶段小节。
- `FlexForge.md`、`docs/06`、ADR 只在路由表指向时读。
- 回复引用 `文件路径 + 章节/行号`，不复制大段原文。
