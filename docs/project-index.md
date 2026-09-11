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
├── README.md                  项目入口（英文主文档）：能力/快速启动/架构/质量
├── README.zh-CN.md            README 中文副本（与英文同构）
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
│   ├── demo-example-inventory.sh   库存插件生命周期演示（安装→CRUD→停启→卸载，P09）
│   ├── demo-e2e.sh                 五场景端到端演示脚本+逐环节计时 CSV（P12）
│   ├── export-thesis-data.mjs      论文四数据面 CSV 导出（compose 内 psql，P11）
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
│   ├── flexforge-plugin/            插件包校验+版本存储+生命周期：P07 导入链路；P08 生命周期（激活/停用/升级/卸载/stale/重启恢复+MigrationScriptRunner+资产 serve）；P21 预设（PluginPresetService/Controller+PresetRepository，快照保存/收敛应用）；P22 处理器输出契约增 chart（OutputValidator 三型校验）
│   ├── flexforge-issue/             Issue/评论/标签/状态机/版本化规格（P10）；P11：clarify/generate 编排+ai_task_log
│   ├── flexforge-ai/                规格 Schema（RequirementSchema v1）+预览派生（P10）；P11：ModelPort+ClarifyEngine+PluginPackageGenerator+prompts（P21 起 v2）；P15：config/（AiEnv/AiConfigRepository/SecretCipher/AiConfigService/AiConfigController）+RoutingModelPort 运行时路由
│   │   └── src/main/resources/prompts/v2/   提示词与 fixture 资源（clarify.md + fixture-spec.json，版本一一对应；v1 为历史保留，P21 起装载 v2）
│   └── flexforge-app/              启动、配置、健康检查；web/ 统一错误装配 + requestId 过滤器 + logback 脱敏基线（P02 迭代 2）；Testcontainers 冒烟 + ArchUnit（5 规则）+ R-GOV-06 fixture 测试 + AgentIssueE2eTest/E2eDemoScript（RB-E2E 五场景×3 干净库，P12）
├── frontend/                       Vue 3 + TS + Vite（P01 骨架 + P06 动态渲染）
│   ├── package.json / package-lock.json   +vue-router；dev 依赖 +@vue/test-utils/happy-dom/globals（P06）；运行依赖 +@fontsource/inter（自托管字体，P16）
│   ├── Dockerfile                  Vite dev 镜像（非 root；生产静态服务 P06 引入）
│   ├── vite.config.ts              dev 同源代理（CORS 基线）+ preview CSP/安全响应头骨架
│   ├── vitest.config.ts            测试配置（@ alias；组件测试用文件级 happy-dom 标注）
│   ├── eslint.config.js / eslint.config.targets.js   硬上限 error / 建议目标 warn（P06 补浏览器 globals）
│   └── src/
│       ├── main.ts / App.vue / router.ts   路由壳（登录守卫体验跳转，安全边界在服务端 S2）
│       ├── api/                    client（错误规范化/令牌注入/401 回调，FormData multipart 不覆盖边界）+ auth/meta/data/plugins（清单+导入/激活/停用/卸载，P15）/processors（P20 清单+invoke）/issues（clarify/迁移/规格/生成，P15）/system/theme 客户端 + 契约类型
│       ├── auth/token.ts           会话令牌（sessionStorage）与当前用户
│       ├── registry/               keyed.ts 通用基座 + renderer/menu/layout/theme/recordAction/locale registry（P15：语言包+t()/语言切换）+ builtinContributions（内置部件/动作/本地菜单：Issue 工作台+插件管理+设置入口）
│       ├── assets/agent-skill/SKILL.md   FlexForge 插件开发 Agent Skill（P24 FR-ISSUE-08 唯一事实源；?raw 内联分发，简报卡下载）
│       ├── styles/                 tokens.css（设计令牌+基建原语，P18 现代极简重订：zinc/indigo 阶取 Tailwind v4 公开值+浅色侧栏派生+--ff-scrim）+ base.css（全局控件/表格/浅色平面侧栏基线，P16 拆分/P18 重订）
│       ├── components/             六类 renderers + StateView（五状态）+ DynamicTable/DynamicForm + LayoutRenderer/EntityCards + ui/（BaseButton/BaseSwitch/ComponentCard/BaseDrawer/ConfirmDialog 统一确认，P16）+ IssueDetail/IssueDevPanel/IssueComments + PluginCard（P21 重构：当前版本+开关+版本收纳）+ UploadDropzone（拖拽+自动校验，P16）+ AppIcon（内联图标集，P16）+ AppLogo + KanbanView（P17 声明式看板/P19 拖拽换列）+ ViewToggle（P17 表格看板切换）+ ListPager/RecordActionsBar（P19 拆分）+ ProcessorDrawer（P20 数据处理器执行/结果渲染） + IssueClarifyChat（P21 对话拆分：气泡/动效/打字指示）+ PluginPresetBar（P21 预设条） + ChartCanvas（P22 图表基建：chart.js 封装条形/饼+令牌调色板+数据表兜底） + IssueChatWorkbench/IssueWorkbenchSidebar/IssueDiscussion（P23 用户端工作台）+ UserBatchBar（P24 批量操作条：内嵌危险确认）+ UserCreateDrawer/UserRolesDrawer/IssueSpecSection/IssueNewRequirementForm（P26 QG-4 拆分）+ utils/pluginConfirm（确认规格纯映射）
│       ├── composables/            useEntityMetadata（metaVersion 比对 → stale 刷新）+ useConfirmAction（P16 统一确认抽 composable）+ useKanbanMove（P19 拖拽编排）/useEntityProcessors（P20 处理器入口） + usePluginPresets（P21 预设单飞与结果呈现） + useUserBatch（P24 用户批量停启用选择与提交）
│       ├── utils/                  csv.ts（P19：RFC 4180 转义+BOM 本地导出，无新端点）+ viewColumns.ts（P19 列解析单点：表格/导出共用，QG-4）
│       └── views/                  Login/Workbench/Home/DynamicEntity（列表/详情/新建/编辑+看板呈现与拖拽换列 P19+CSV 导出 P19）/Issues（Issue 工作台+创建抽屉，P15）/Plugins（清单+写操作，P15）/Users（用户管理+批量停启用 P24）/Audit（审计日志，P24）/Placeholder（居中空态 P24）
├── database/
│   ├── migrations/V001__init.sql   平台骨架表（sys_user/sys_role/sys_user_role）
│   ├── migrations/V002-004         V002 审计表 / V003 角色种子 / V004 meta_entity+meta_field+meta_view（P04）
│   ├── migrations/V005__data_record.sql 动态记录单 JSONB 表 + GIN 索引（P05，docs/03 §4 存储定案）
│   ├── migrations/V006__plugin_tables.sql plugin_instance/version/dependency/migration 版本存储（P07）
│   ├── migrations/V007__plugin_lifecycle.sql plugin_activation/registration/audit_event + plugin_version 载荷列（P08）
│   ├── migrations/V008__plugin_asset_payloads.sql 资产载荷列（asset_payloads，P08）
│   ├── migrations/V009__plugin_activation_occupancy.sql 同插件唯一占用部分唯一索引（P09 前置）
│   ├── migrations/V010__issue_tables.sql issue/标签/评论/迁移记录/requirement_spec 版本化规格（P10）
│   ├── migrations/V011__ai_task_log.sql AI 任务结构化记录（P11，docs/12 实验数据）
│   └── init/                       Compose 首次初始化：应用专用账号
├── plugins/
│   ├── example-inventory/          库存示例 Level 1 包（P09：plugin.json+实体+两视图+两迁移；纯包目录无 README）
│   ├── example-inventory-README.md 安装说明（包外，P07 区域白名单不允许包内文档）
│   ├── example-library/            图书借阅示例包（P15：decimal/boolean/date 字段形态+种子）
│   ├── example-facility/           设备巡检示例包（P15：integer/boolean/date 字段形态）
│   ├── example-kanban/             任务管线看板示例包（P17：list/form/kanban 三视图，声明即得看板）
│   ├── example-quality/            来料检验示例包（P19：integer/boolean/date/enum+幂等种子）
│   ├── example-workorder/          生产工单示例包（P19：状态看板，拖拽换列=状态流转）
│   ├── example-purchase/           采购订单示例包（P19：decimal 金额字段形态+幂等种子）
│   ├── example-safety/             安全隐患示例包（P19：整改闭环状态看板+幂等种子）
│   ├── example-analytics/          表格分析处理器包（P20：capabilityLevel=2，三个 Python 数据处理器——月度透视/合格率/负载汇总，纯标准库）
│   ├── locale-en/                  英文语言包（P15 起；P26 升版全键集——系统 chrome 全覆盖，剔插件内容键）
│   ├── locale-ja/                  日文语言包（P26：与 en 同键集，FR-SETUP-02）
│   ├── locale-fr/                  法文语言包（P26：与 en 同键集）
│   ├── locale-es/                  西班牙文语言包（P26：与 en 同键集）
│   ├── theme-default/              默认主题 Level 1 包（P12.5：SVG 背景/标/动效 + tokens 键值表）
│   └── theme-warm/                 暖色覆盖主题包（P12.5：同名 tokens 覆盖证明插件可自定义样式）
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
    ├── 14-defense-delivery-guide.md   答辩交付运行手册（启动/账号/演示/三保险/排障）
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
| `README.md` | 项目入口（英文主文档） | 首次进入项目 |
| `README.zh-CN.md` | README 中文副本 | 首次进入项目（中文） |
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
| `docs/14-defense-delivery-guide.md` | 答辩演示启动/账号/流程/离线三保险/故障排查 | 演示、验收与答辩准备 |
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
| 2026-08-29 | §1 树更新：V011 ai_task_log；ai +ModelPort/HttpModelPort/FixtureModelPort/ClarifyEngine/PromptTemplates/PluginPackageGenerator +prompts/v1 资源；issue +AiTaskLogPort/IssueAiService/IssueAiConfig +clarify/generate 端点；ErrorCodes +model_unavailable/model_output_invalid；scripts +export-thesis-data.mjs；app 测试 +IssueAiApiTest | P11：AI 适配器与生成器（RB-AI） |
| 2026-08-29 | §1 树更新：scripts +demo-e2e.sh（五场景演示+计时；顺带回填 P09/P11 遗漏的 demo-example-inventory.sh 与 export-thesis-data.mjs 两行）；frontend api +plugins.ts、views +PluginsView、router +/plugins、builtinContributions +本地菜单；app 测试 +AgentIssueE2eTest/E2eDemoScript | P12：Agent Issue 端到端闭环（RB-E2E + 插件页，docs/12 §"端到端演示各环节耗时"数据源落地） |
| 2026-08-29 | §1 树/文档地图 +docs/14-defense-delivery-guide.md（答辩运行手册：启动/账号/演示/三保险/排障/清理），docs/05 索引同步登记 | P14 迭代 2：答辩交付固化（docs/09 P14"故障排查手册/演示账号/三保险"落位） |
| 2026-08-30 | §1 树更新：plugins +theme-default/theme-warm（P12.5 主题包）；frontend +styles/tokens.css、components/ui/ 四组件、views +Users、api +system/theme；backend plugins 聚合端点 theme-assets + kind=tokens；app 测试 +ThemeAssetApiTest | P12.5：前端基建与默认主题（用户裁决新增阶段，三缺陷修复+组件库+换肤通道闭环） |
| 2026-08-30 | §1 树更新：frontend api +issues.ts、plugins.ts 扩写操作（multipart FormData）；views +Issues、components +IssueDetail/IssueDevPanel/PluginCard；router +/issues、builtinContributions +Issue 工作台菜单 | P15 迭代 1：Issue/AI 工作台前端（clarify 对话入口/迁移/规格/生成消费面）+ 插件管理页写操作（导入/激活/停用/卸载） |
| 2026-08-30 | §1 树更新：V012 ai_provider_config；flexforge-ai +config 包与 RoutingModelPort（两 ModelPort 实现退出 Bean 装配）；frontend +api/settings、views +Settings、router +/settings、菜单 +设置入口 | P15 迭代 2：设置页 + AI 运行时配置（FR-SETUP-01；docs/13 S4/§3.6-5 第二密钥通道修订、docs/02 +FR-SETUP、docs/03 §8 +/ai/config） |
| 2026-08-30 | §1 树更新：plugins +example-library/example-facility/locale-en；frontend +registry/localeRegistry、components +AppLogo、views/SettingsView 增语言卡、LoginView 品牌化重写、tokens.css +入场动效；backend 两处 KINDS 扩 locale；app 测试 +ExamplePluginsP15Test | P15 迭代 3：i18n 通道+英文化插件+语言切换+登录/工作台品牌化+两个示例业务插件（登记册 §2.2 变更同步） |
| 2026-08-31 | §1 树更新：frontend +styles/base.css、components +AppIcon/ConfirmDialog/IssueComments/UploadDropzone、依赖 +@fontsource/inter；api +apiErrorMessage、issues +TRANSITION_LABELS；registry recordAction +confirm；plugins/locale-en 1.0.3（+分组键） | P16 迭代 1/2：交互重构+文案净化（统一确认/迁移按钮组/上传区/列表头部创建）与默认主题精修（导航分组图标/控件 focus 环/自托管字体）——用户裁决新增阶段 |
| 2026-09-03 | §1 树无结构变更；styles/ 行描述更新（P18 现代极简重订：zinc/indigo 令牌+浅色平面侧栏+--ff-scrim）；plugins/theme-default 1.1.0（tokens 对齐+bg/logo/pulse 重绘）、theme-warm 1.1.0（stone/orange 适配） | P18：前端现代极简风格化（用户裁决新增阶段；色板取 Tailwind v4 公开 oklch 值，无运行时外链） |
| 2026-09-04 | §1 树更新：plugins +example-quality/example-workorder/example-purchase/example-safety（P19 四包）并回补 example-kanban（P17 存量遗漏）；components +KanbanView/ViewToggle；views DynamicEntity 行补看板拖拽/CSV；src +utils/csv.ts；app 测试 +ExamplePluginsP19Test | P19：生产企业插件矩阵与前端动效完善（用户裁决新增阶段；复用既有扩展点，登记册零变更） |
| 2026-09-07 | §1 树更新：plugins +example-analytics（P20 Level 2 处理器包）；components +ProcessorDrawer；composables +useKanbanMove/useEntityProcessors；api +processors；plugin 模块 +ProcessorSpec/ProcessorExecutionException/ProcessorRunner/ProcessorService/ActiveProcessorLocator/ProcessorController（+data 依赖）；backend 测试 +ExamplePluginsP20Test；Dockerfile 运行层 +python3 | P20：插件代码处理器——Python 表格处理（用户裁决激活 ADR-0002 Level 2；S6 修订双轨校验；登记册 +extension.data-processor） |
| 2026-09-07 | §1 树更新：V014 plugin_preset；plugin 模块 +PluginPresetService/PluginPresetController/domain PresetRepository/infra JdbcPresetRepository；prompts v1→v2（clarify.md+fixture-spec）；components +IssueClarifyChat/PluginPresetBar、PluginCard 重构、IssueDetail 瘦身；composables +usePluginPresets；api plugins.ts 扩预设四函数+upgradeVersion；AppIcon +send；app 测试 +PluginPresetApiTest；ai 断言 fixture-clarify-v2 | P21：插件管理操作逻辑与 Issue 工作台对话体验（用户裁决新增阶段；FR-PLUGIN-12/FR-ISSUE-03A；提示词 v2） |
| 2026-09-08 | §1 树更新：example-analytics 升 0.2.0（+purchase-chart-monthly/quality-chart-share 双图处理器）；components/ui +ChartCanvas；styles tokens +--ff-chart-c1..c6；api processors.ts 增 chart 联合变体；app 测试 +ExamplePluginsP22Test、P20/P20Security 构包基线随 0.2.0 同步（五脚本+确定性激活） | P22：图表渲染基建与处理器图表输出（用户裁决新增阶段；FR-CHART-01/FR-PLUGIN-13；新依赖 chart.js 4.5.1 单独提交） |
| 2026-09-09 | §1 树更新：database/migrations +V015（issue 发布/简报）+V016（processor_artifact）；backend prompts +v3（clarify/fixture-spec）；flexforge-plugin +FileProcessorService/ProcessorArtifactStore/ProcessorDeclarationValidator/ProcessorRuntimeConfig/artifact 端口与 JDBC/Runner.runFile；flexforge-issue publish 端点+USER 收口；flexforge-common ProcessorContribution 扩字段+新错误码；flexforge-system 菜单 +file-tools；frontend views +ToolsView、components +IssueChatWorkbench/IssueWorkbenchSidebar/IssueDiscussion/IssueBriefCard/ProcessorResultView、utils +xlsx、router +/tools、IssuesView 角色分支；plugins +example-filetools；app 测试 +IssuePublishApiTest/ExampleFileToolsApiTest | P23：体验补全与文件工具插件（XLSX 导出/Issue 角色分置+提示词 v3/文件处理器，FR-META-06/FR-ISSUE-03B/07/FR-PLUGIN-14；PR #47 缺陷修复随段） |
| 2026-09-10 | §1 树更新：frontend +assets/agent-skill/SKILL.md（Agent Skill 唯一事实源）、views +AuditView、composables +useUserBatch、components +UserBatchBar；IssueBriefCard +下载 Skill；DynamicForm +cancelLabel；flexforge-system +batch-status 端点+菜单 system-audit；app 测试 +UserBatchStatusApiTest、前端 +PluginCard/AuditView 测试 | P24：管理面补全与体验打磨（用户裁决新增阶段；FR-AUTH-04/05/FR-ISSUE-08；插件卡降噪/排版居中/表单取消/Issue 切换清空） |
| 2026-09-10 | §1 树更新：frontend 依赖 +@fontsource/space-grotesk+@fontsource/noto-sans-sc（P25 字体系统）；styles tokens/base 增 display 字体/字号阶/ff-form-grid/ff-page-title/骨架纹理基线；views 八处 h2 接 ff-page-title；plugins theme-default 升 1.1.2（bg 重绘）、theme-warm 升 1.1.2（+assets/bg.svg） | P25：前端排版分布与视觉精修（用户裁决新增；排版均匀分布+字体层级+背景纹理，文案零变更） |
| 2026-09-11 | §1 树更新：plugins +locale-ja/locale-fr/locale-es（1.0.0）、locale-en 升 1.1.0（344 键全键集）；frontend +localePacks.test/pluginsViewFixtures、components +UserCreateDrawer/UserRolesDrawer/IssueSpecSection/IssueNewRequirementForm、utils +pluginConfirm、api/issues +issueStatusLabel/transitionLabel、registry LANGUAGE_LABELS 扩三语；backend flexforge-ai +ModelUrlGuard/ModelConfigGate/HttpModelConfigGate/ModelEndpoints/AiConfigKernel/AiConfigWiring、app 测试 +AiConfigProbeApiTest | P26：界面净化、系统多语言与 AI 上游探活（用户裁决新增；FR-AUTH-06/FR-PLUGIN-15/FR-SETUP-01/02 增补） |
| 2026-09-11 | §1 树更新：根 +README.zh-CN.md（中文副本，README.md 重写为英文主文档+徽章+截图+文档表）、docs +assets/（工作台/插件页截图）；§3 文档地图同步 | 用户裁决：远端仓库门面完整化——README 英文为主+中文副本+仓库简介/topics（P26 后续） |
