# FlexForge 项目索引（文件级定位入口）

**版本**：v1.0
**状态**：active
**日期**：2026-08-20

> 本文是唯一的文件级索引。`AGENTS.md` 不复制本文内容、只保存指针（保持 AGENTS.md 稳定，提升会话 token 缓存命中率）。
> 需要定位"改哪里、读什么"时，先 `grep` 本文对应节，不要通读。
> 自更新规则见 §7；阶段进度不写这里（看 `STATUS.md`）。

## 1. 当前结构（P01 前，文档阶段）

```text
FlexForge/
├── README.md                  项目入口与状态摘要
├── STATUS.md                  进度唯一锚点（当前阶段/下一步/阻塞）
├── AGENTS.md                  Agent 每轮注入入口（文档路由 + 持久约束）
├── CONTRIBUTING.md            人的开发流程
├── FlexForge.md               早期概念稿（历史，冲突时以 docs/ 为准）
├── .editorconfig / .gitattributes / .gitignore
├── .github/
│   ├── workflows/ci.yml            CI（仓库卫生、密钥扫描；backend/frontend/docker/audit 条件激活）
│   └── dependabot.yml              依赖版本更新（actions 周更；npm/maven/docker P01 启用）
├── scripts/
│   └── check-repo-health.mjs       本地/CI 仓库健康检查（R-GOV-04/05 + 卫生）
└── docs/
    ├── 00-feasibility-review.md       可行性评审与 MVP 边界
    ├── 01-project-plan.md             里程碑与变更控制
    ├── 02-requirements.md             需求规格（FR/NFR 编号唯一来源）
    ├── 03-architecture.md             总体架构、骨架与业务边界
    ├── 04-test-strategy.md            测试分层与数据库隔离
    ├── 05-documentation-guide.md      文档分工与更新规则
    ├── 06-dsh-reference-study.md      dsh 参考研究（按需）
    ├── 07-plugin-runtime-data-model.md 插件运行时持久化对象
    ├── 08-implementation-blueprint.md 可编码蓝图（模块/接口/插件格式）
    ├── 09-detailed-implementation-plan.md P00-P14 阶段计划与验收
    ├── 10-engineering-governance.md   治理基线（红线/缝/契约）
    ├── 11-regression-test-plan.md     回归分层与 R-GOV 自检
    ├── 12-thesis-experiment-plan.md   论文实验与数据收集计划
    ├── 13-security-baseline.md        应用安全基线（威胁模型、S 红线、分域基线）
    ├── coding-standards.md            编码规范与数值硬约束（§7）
    ├── repository-maintenance.md      仓库/分支/环境/Issue 约束
    ├── extension-points.md            扩展点唯一登记册
    ├── project-index.md               本文（文件级索引）
    ├── project-status.json            状态机器镜像
    └── adr/
        ├── 0001-mvp-architecture.md
        ├── 0002-runtime-plugin-model.md
        ├── 0003-engineering-governance.md
        ├── 0004-skeleton-vs-business.md
        └── 0005-plugin-migrations.md
```

## 2. 目标结构（P01 起）

```text
backend/                模块化单体（每个模块 api -> application -> domain -> infrastructure）
  flexforge-app/        启动、配置、REST 装配、健康检查
  flexforge-common/     ID、错误模型、分页、JSON、时间、日志
  flexforge-runtime/    PluginRuntime、Context、Service/Extension Registry
  flexforge-auth/       登录、JWT、RBAC、当前用户
  flexforge-system/     用户、角色、菜单、审计
  flexforge-meta/       Entity/Field/View 定义与 MetaRegistry
  flexforge-data/       动态数据访问、查询白名单、记录校验
  flexforge-plugin/     包校验、版本、依赖、激活、回滚、插件迁移 runner
  flexforge-issue/      Issue、评论、标签、状态机
  flexforge-ai/         模型端口、规格 Schema、插件生成器
  flexforge-workflow/   可选（P13 之后）
frontend/               Vue 3 + TS（core/components/views/dynamic）
database/
  migrations/           仅平台骨架迁移（Flyway）；业务迁移在插件包内
  seed/                 演示数据（幂等）
plugins/                预置与示例插件（example-inventory，Level 1）
tests/
  fixtures/             违规样例/插件包/AI/元数据 fixture
  e2e/                  端到端冒烟
scripts/                check-repo-health 等可重复脚本
```

## 3. 文档地图（按文件）

| 文件 | 作用 | 何时读 |
| --- | --- | --- |
| `README.md` | 项目入口、当前状态、非目标 | 首次进入项目 |
| `STATUS.md` | 进度唯一锚点 | 每轮开始（锚点块） |
| `AGENTS.md` | Agent 路由与每轮约束 | 已注入；按 § 查规则 |
| `docs/02-requirements.md` | FR/NFR 编号 | 任何实现/测试/文档变更前找编号 |
| `docs/03-architecture.md` | 架构边界、骨架/业务边界、扩展点摘要 | 设计或边界改动 |
| `docs/08-implementation-blueprint.md` | 模块边界、接口、插件格式、迁移 runner | 编码前 |
| `docs/09-detailed-implementation-plan.md` | 当前阶段任务与验收 | 按阶段小节读 |
| `docs/10-engineering-governance.md` | 红线 R1-R9、seam 预留、契约演化 | 后端/契约改动 |
| `docs/11-regression-test-plan.md` | 回归分层、R-GOV | 测试/CI/门禁任务 |
| `docs/12-thesis-experiment-plan.md` | 论文指标、数据埋点与导出 | P06 前定稿；P11 起核对埋点 |
| `docs/13-security-baseline.md` | 威胁模型、S1-S9 安全红线、分域基线、阶段映射 | 认证/权限/上传/AI/密钥/CORS 改动前必读 |
| `docs/coding-standards.md` | 命名、注释、UTC、数值硬约束 | 任何代码 |
| `docs/repository-maintenance.md` | 分支/PR/环境/迁移/Issue | Git、环境、迁移 |
| `docs/extension-points.md` | 扩展点登记册 | 新增/使用扩展点 |
| `docs/04-test-strategy.md` | 测试分层、数据库隔离 | 写测试 |
| `docs/05-documentation-guide.md` | 文档分工、更新规则 | 改文档 |
| `docs/07-plugin-runtime-data-model.md` | 插件持久化对象与必测用例 | 插件生命周期 |
| `docs/adr/0001-0005` | 已定架构决策 | 相关决策或复审 |
| `docs/06-dsh-reference-study.md` | 外部参考研究 | 需要背景时 |
| `FlexForge.md` | 早期概念稿 | 仅论文/历史背景 |

## 4. 代码 → 文档映射（P01 后使用）

| 改动对象 | 必读 | 产出约束 |
| --- | --- | --- |
| 后端新增/修改接口 | `03` §2/§8、`08` §2、`02` 对应 FR | 统一错误码、`/api/v1`、DTO 分离 |
| 字段类型/元数据 | `09` P04、`10` R3、`coding-standards` §7 | `FieldTypeRegistry` 单点，禁散落分支 |
| 动态数据读写 | `09` P05、`10` R3、`03` §4 | 只走 `service.data-access`，物理删除+审计 |
| 插件包校验/导入 | `08` §4、`extension-points` §2.4、`09` P07 | 10MB 上限、zip-slip、schemaVersion |
| 插件迁移 | `adr/0005`、`07`、`08` §3.3 | runner 非 Flyway，越界拒绝 |
| 插件生命周期 | `adr/0002`、`07`、`09` P08 | activationId 绑定、可撤销 |
| 前端渲染 | `08` §6、`coding-standards` §3 | 禁 `v-html`/`eval`，keyed registry |
| 平台数据库迁移 | `repository-maintenance` §5/§9、`adr/0005` | 只含骨架表，UTC，幂等 |
| AI 适配/生成 | `03` §6、`09` P11、`coding-standards` §5 | `ModelPort`，提示词文件化，密钥 env |
| 测试/门禁 | `04`、`11` | 一次性 schema，R-GOV 不漂移 |
| 状态/文档/索引 | `05`、`STATUS.md`、本文 §7 | 同轮更新 + 变更记录 |

## 5. 规则触发 → 定位速查（被阻断时）

| 症状 / 错误 | 读哪里 |
| --- | --- |
| 文件超长、复杂度/参数/嵌套超标 | `coding-standards.md` §7 |
| 循环依赖 / 跨模块 infra 引用 | `10` §2 R1、§5 |
| 未登记扩展点 / 无消费方 | `extension-points.md` §1 |
| `unsupported_schema_version` / 非法贡献 | `extension-points.md` §2.4、`09` P07 |
| `stale_activation` / 迁移失败 | `07` §3-5、`08` §3.3、`adr/0005` |
| 骨架里出现业务代码 | `adr/0004`、`03` §3.5 |
| 阻塞 / 需要拍板 | `AGENTS.md` §5、`STATUS.md` |
| 提交 / 分支 / PR / Issue 疑问 | `repository-maintenance.md` §2/§8、`AGENTS.md` §6 |
| 测试连错库 / 破坏性测试 | `04` §2、`repository-maintenance.md` §9 |
| 文档/镜像漂移 | `05`、R-GOV-04/05（`11` §3） |

## 6. 关键契约速查

- 扩展点：`extension-points.md`（`service.meta`/`service.data-access`/`service.audit`/`extension.navigation`/`extension.field-renderer`/`extension.record-action`/`event.domain`）
- API：`/api/v1/`；统一响应 `{code, message, requestId, details?}`
- 分页：默认 `pageSize=20`，上限 `200`；排序白名单
- 时间：UTC 存储，ISO-8601 出参
- 插件：`schemaVersion=1`、Level 1 声明式、单包压缩 ≤ 10MB（解压后 ≤ 50MB）、`minPlatformVersion`
- 数据删除：物理删除 + 审计；MVP 无软删除
- 版本：项目 `0.1.0-SNAPSHOT`

## 7. 自更新规则

1. **触发**：新增/删除/移动目录、模块、长期文档、ADR、扩展点或关键契约；需求编号体系变化。
2. **动作**：同一轮更新本文对应节 + §8 变更记录（日期、变更内容、原因）；涉及文档索引的同步 `README.md` 与 `docs/05`（R-GOV-05 校验）。
3. **约束**：不在 `AGENTS.md` 复制结构树；AGENTS 只保存指向本文的指针，保持其稳定以提升缓存命中率。
4. **边界**：阶段进度不写本文（看 `STATUS.md`）；本文只描述"结构与定位"。
5. **验证**：R-GOV-05 校验本文列出的路径全部存在、新增长期文档已登记、变更记录有对应行。

## 8. 变更记录

| 日期 | 变更 | 原因 |
| --- | --- | --- |
| 2026-08-20 | v1.0 建立；项目结构从 AGENTS.md 迁出 | 结构与索引变动频繁，与每轮注入文件分离以降低 token 成本 |
| 2026-08-20 | 新增长期文档 `docs/12-thesis-experiment-plan.md`（§1/§3 同步登记）；§6 契约速查补解压后大小上限 | 论文实验数据收集前置到 P11 埋点；P07 上传安全基线补强 |
| 2026-08-20 | 新增长期文档 `docs/13-security-baseline.md`（§1/§3 同步登记） | 安全要求此前散落多份文档，建立唯一归属：威胁模型 + S1-S9 红线 + 分域基线 + 阶段映射 |
| 2026-08-20 | 新增 `.github/`（ci.yml + dependabot.yml）与 `scripts/check-repo-health.mjs`（§1/§3 同步登记） | 编码开始前先立 CI 门禁：R-GOV 基础与卫生检查即时生效，构建类任务条件激活 |
