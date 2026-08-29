# FlexForge 项目索引（文件级定位入口）

**版本**：v1.0
**状态**：active
**日期**：2026-08-20

> 本文是唯一的文件级索引。`AGENTS.md` 不复制本文内容、只保存指针（保持 AGENTS.md 稳定，提升会话 token 缓存命中率）。
> 需要定位"改哪里、读什么"时，先 `grep` 本文对应节，不要通读。
> 自更新规则见 §7；阶段进度不写这里（看 `STATUS.md`）。

## 1. 当前结构（P02 迭代 1）

```text
FlexForge/
├── README.md                  项目入口、快速启动与状态摘要
├── STATUS.md                  进度唯一锚点（当前阶段/下一步/阻塞）
├── AGENTS.md                  Agent 每轮注入入口（文档路由 + 持久约束）
├── CONTRIBUTING.md            人的开发流程
├── FlexForge.md               早期概念稿（历史，冲突时以 docs/ 为准）
├── .editorconfig / .gitattributes / .gitignore / .dockerignore
├── .env.example                   环境变量占位（DB_* 与 Compose 宿主端口；真实配置已被 gitignore 排除）
├── .github/
│   ├── workflows/ci.yml            CI（repo-health 全量门禁、gitleaks、backend/frontend/docker/audit）
│   └── dependabot.yml              依赖更新（actions/npm/maven/docker 周更，P01 全启用）
├── docker-compose.yml             db + backend + frontend，端口只绑 127.0.0.1（8088/5173）
├── scripts/
│   ├── check-repo-health.mjs       一条命令：格式/lint/test/build + R-GOV-01/02/03/04/05/06
│   ├── sync-status.mjs             STATUS.md → project-status.json 单向生成/校验
│   └── lib/
│       ├── gates.mjs              前后端门禁执行层（ComSpec/工具链/R-GOV-02/06）
│       ├── rgov-extension-points.mjs R-GOV-03 门禁（登记册常量比对 + 解析器负样本自检，P02）
│       ├── rgov-thresholds.mjs     R-GOV-01 简化版阈值解析与比对
│       └── status.mjs              STATUS 锚点/阶段看板单一解析器（R-GOV-04 共用）
├── backend/                        Maven 多模块（Wrapper 3.9.16，目标 Java 17）
│   ├── config/checkstyle.xml       Checkstyle 阈值（数值唯一来源 docs/coding-standards §7）
│   ├── Dockerfile                  多阶段构建，非 root 运行
│   ├── flexforge-common/           平台契约：ApiConstants/RequestIds/@PublicApi/@ExperimentalApi + contract/api/audit/registry 子包（P02 迭代 1）
│   ├── flexforge-runtime/          PluginContext + 内存 ServiceRegistry/ExtensionRegistry/事件发布器 + 注册-撤销测试（P02 迭代 1）
│   ├── flexforge-auth/             登录/JWT/RBAC/当前用户：BCrypt+防暴破+JwtAuthFilter+@RequireRole（P03）
│   ├── flexforge-system/           用户/角色/菜单/审计：管理接口+菜单聚合（extension.navigation 消费方）+审计查询+落库（P03）
│   ├── flexforge-meta/             元数据写模型：FieldTypeRegistry 单点+MetaRegistry 缓存/版本+实体/字段/视图配置 API（service.meta，P04）
│   ├── flexforge-data/             动态数据访问：data_record 单 JSONB 存储+实体级记录校验+白名单 SQL 构造+动态 CRUD API（service.data-access，P05）
│   ├── flexforge-plugin/            插件包校验+版本存储+生命周期：P07 导入链路；P08 生命周期（激活/停用/升级/卸载/stale/重启恢复+MigrationScriptRunner+资产 serve）
│   ├── flexforge-issue/             Issue/评论/标签/状态机/版本化规格（P10；docs/03 §7 迁移矩阵+规格门）
│   ├── flexforge-ai/                规格 Schema（RequirementSchema v1）+插件资源预览派生（P10；P11 扩展适配器/生成器）
│   └── flexforge-app/              启动、配置、健康检查；web/ 统一错误装配 + requestId 过滤器 + logback 脱敏基线（P02 迭代 2）；Testcontainers 冒烟 + ArchUnit（5 规则）+ R-GOV-06 fixture 测试
├── frontend/                       Vue 3 + TS + Vite（P01 骨架 + P06 动态渲染）
│   ├── package.json / package-lock.json   +vue-router；dev 依赖 +@vue/test-utils/happy-dom/globals（P06）
│   ├── Dockerfile                  Vite dev 镜像（非 root；生产静态服务 P06 引入）
│   ├── vite.config.ts              dev 同源代理（CORS 基线）+ preview CSP/安全响应头骨架
│   ├── vitest.config.ts            测试配置（@ alias；组件测试用文件级 happy-dom 标注）
│   ├── eslint.config.js / eslint.config.targets.js   硬上限 error / 建议目标 warn（P06 补浏览器 globals）
│   └── src/
│       ├── main.ts / App.vue / router.ts   路由壳（登录守卫体验跳转，安全边界在服务端 S2）
│       ├── api/                    client（错误规范化/令牌注入/401 回调）+ auth/meta/data 客户端 + 契约类型
│       ├── auth/token.ts           会话令牌（sessionStorage）与当前用户
│       ├── registry/               keyed.ts 通用基座 + renderer/menu/layout/theme/recordAction registry + builtinContributions（内置部件与动作）
│       ├── components/             六类 renderers + StateView（五状态）+ DynamicTable/DynamicForm + LayoutRenderer/EntityCards
│       ├── composables/            useEntityMetadata（metaVersion 比对 → stale 刷新）
│       └── views/                  Login/Workbench/Home/DynamicEntity（列表/详情/新建/编辑）/Placeholder
├── database/
│   ├── migrations/V001__init.sql   平台骨架表（sys_user/sys_role/sys_user_role）
│   ├── migrations/V002-004         V002 审计表 / V003 角色种子 / V004 meta_entity+meta_field+meta_view（P04）
│   ├── migrations/V005__data_record.sql 动态记录单 JSONB 表 + GIN 索引（P05，docs/03 §4 存储定案）
│   ├── migrations/V006__plugin_tables.sql plugin_instance/version/dependency/migration 版本存储（P07）
│   ├── migrations/V007__plugin_lifecycle.sql plugin_activation/registration/audit_event + plugin_version 载荷列（P08）
│   ├── migrations/V008__plugin_asset_payloads.sql 资产载荷列（asset_payloads，P08）
│   ├── migrations/V009__plugin_activation_occupancy.sql 同插件唯一占用部分唯一索引（P09 前置）
│   ├── migrations/V010__issue_tables.sql issue/标签/评论/迁移记录/requirement_spec 版本化规格（P10）
│   └── init/                       Compose 首次初始化：应用专用账号
├── plugins/
│   ├── example-inventory/          库存示例 Level 1 包（P09：plugin.json+实体+两视图+两迁移；纯包目录无 README）
│   └── example-inventory-README.md 安装说明（包外，P07 区域白名单不允许包内文档）
├── tests/
│   └── fixtures/
│       ├── fixtures.json           条目清单 + SHA-256（R-GOV-06 哈希校验）
│       └── violations/             故意违规样例（超行数/高复杂度/长方法，不参与正常构建）
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
    ├── project-status.json            状态机器镜像（sync-status 生成）
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
| 2026-08-20 | §1 树更新：backend/ 多模块、database/（migrations + init）、docker-compose.yml、.env.example；§2 目标结构对应落地 | P01 迭代 1 后端骨架落地（Boot 4.0.7 + Flyway V001 + Compose 链路验证通过） |
| 2026-08-21 | §1 树更新：frontend/ Vue3+TS 骨架与工具链、backend/config/checkstyle.xml、tests/fixtures/、scripts/sync-status.mjs 与 scripts/lib/；Compose 三服务；Dependabot 全生态启用 | P01 迭代 2/3：前端骨架、lint/格式/ArchUnit 门禁、R-GOV-01/02/06 激活、状态脚本落地 |
| 2026-08-23 | §1 树更新：flexforge-runtime 新模块、flexforge-common 四个子包（contract/api/audit/registry）、app ArchUnit 扩至 5 规则；R-GOV-03 门禁激活 | P02 迭代 1：契约层 + 内存注册表 + 登记册常量落地（Issue #5 修复经 PR #7 先行合并） |
| 2026-08-24 | §1 树更新：app web/（RequestIdFilter/GlobalExceptionHandler）与 logback-spring.xml；R-GOV-08 门禁激活、R-GOV-01 升级完整版（防削弱检查）；§7 裁决 record 组件不计入参数上限（Issue #9） | P02 迭代 2：REST 统一错误装配 + requestId 全链路 + 日志脱敏；Mimosa 深度扫描 0 findings |
| 2026-08-24 | §1 树更新：flexforge-auth 与 flexforge-system 新模块；V002 审计表 + V003 角色种子；.env/compose 增加 AUTH_JWT_* 与引导管理员占位 | P03 迭代 1：认证内核（BCrypt/JWT/防暴破/统一错误）+ 审计落库 |
| 2026-08-24 | §1 树更新：auth 增 @RequireRole/RoleAuthorizationInterceptor；system 增 api/application/infrastructure 三层（用户管理/菜单/审计查询）；common 增 NavigationContribution 与 AuditEvents | P03 迭代 2：用户/角色管理 + 菜单三角色差异（extension.navigation 首个真实消费方）+ 审计查询 |
| 2026-08-24 | §1 树更新：flexforge-meta 新模块（domain/application/infrastructure/api 四层）；V004 meta_* 三表；登记册 field-renderer 行落定六类内置 renderer ID | P04 迭代 1：FieldTypeRegistry 单点 + MetaRegistry 缓存/版本失效 + 实体/字段/视图配置 API + breaking/additive 规则（RB-META） |
| 2026-08-24 | §1 树更新：flexforge-data 新模块（四层）；V005 data_record；登记册 service.data-access 行标注 P05 落地 | P05 迭代 1：动态数据运行时（CRUD API + 实体级记录校验 + 白名单 SQL + RB-DATA） |
| 2026-08-25 | §1 树更新：frontend 新增 api/auth/registry/components/composables/views 分层与 router.ts；依赖 +vue-router、dev +@vue/test-utils/happy-dom/globals；后端 meta 增 by-name 端点 | P06 迭代 1：前端动态渲染核心（RB-UI） |
| 2026-08-25 | §1 树更新：registry 增 layout/theme/recordAction/builtinContributions；components 增 LayoutRenderer/EntityCards；common ExtensionPoints +LAYOUT/THEME_ASSET | P06 迭代 2：GUI 澄清消费面（FR-PLUGIN-10/11） |
| 2026-08-28 | §1 树更新：flexforge-plugin 新模块（四层）；V006 plugin_* 四表；ErrorCodes +unsupported_schema_version；app multipart 上限配置 | P07：插件包校验与版本存储（RB-PLUGIN-VALID） |
| 2026-08-29 | §1 树更新（补记）：V007 plugin_activation/registration/audit_event + plugin_version 载荷列；flexforge-plugin 生命周期四层（ActivationStatus 状态机/LifecycleRepository 端口/PluginLifecycleService+MigrationScriptRunner+PluginContributionFactory/PluginLifecycleController）；common +StaleActivationException；app 测试 +PluginPackageTestSupport/PluginActivationApiTest | P08 迭代 1：PluginRuntime 生命周期（PR #21，RB-PLUGIN-LIFE） |
| 2026-08-29 | §1 树更新：V008 plugin_version.asset_payloads；flexforge-plugin +ThemeAssetSpec/PluginAssetService/PluginRestoreRunner；common +ThemeAssetContribution；app 测试 +PluginAssetApiTest | P08 迭代 2：theme-asset 注册+资产存储/serve 端点（Issue #20 第 3/4 项） |
| 2026-08-29 | §1 树更新：V009 占用唯一索引；plugins/ 目录（example-inventory 包+包外 README）；flexforge-plugin +PluginInventoryService/视图注册端口；app 测试 +ExampleInventoryPluginTest；scripts +demo-example-inventory.sh +lib/rgov-skeleton-purity.mjs（R-GOV-09 激活） | P09：库存示例插件（FR-DEMO-01..03、NFR-SKEL-01、Issue #22 第 1/2 项守卫） |
| 2026-08-29 | §1 树更新：flexforge-issue/flexforge-ai 新模块；V010 issue_* 五表；ErrorCodes +invalid_transition；app 测试 +IssueApiTest | P10：Issue 与规格 Schema（RB-ISSUE） |
