# FlexForge 回归测试计划

**版本**：v1.0
**状态**：active（P01 起逐步落地）
**日期**：2026-08-20
**适用范围**：三条核心链路（动态 CRUD、插件生命周期、Agent Issue）与治理约束本身

## 1. 目标

回归测试要回答两个问题：

1. 改动代码后，之前能跑的主链路是否还能跑（功能回归）。
2. 治理约束（文件上限、复杂度、依赖方向、扩展点登记、状态镜像）有没有被"悄悄放宽"或配置漂移（治理自检）。

本文不替代 [测试策略](./04-test-strategy.md) 的分层测试，只定义**何时跑什么、门禁如何自检、回归包如何随阶段交付**。

## 2. 回归分层与触发时机

| 层级 | 内容 | 触发时机 | 时限目标 |
| --- | --- | --- | --- |
| L1 提交冒烟 | 格式、lint、type-check、单元测试、§3 治理自检（R-GOV） | 本地提交前 + CI push | < 3 分钟 |
| L2 合并回归 | 全量单元/集成测试、依赖边界、Schema/契约 fixture 重放 | 合并到 `main` 前 | 不限，必须全绿 |
| L3 阶段回归 | 本阶段 + 全部上游阶段回归包 + E2E 冒烟 | 每阶段退出条件 | 阶段验收证据 |
| L4 发布候选 | 干净环境重建（Docker Compose + 空库迁移 + 演示脚本）+ 全量回归 | P14 交付前 | 记录运行证据 |

规则：

1. 测试只增不减；禁止通过删测试、改 fixture、放宽阈值来"变绿"。
2. 确定性失败先修复；flaky 测试必须修复或隔离，隔离必须携带 Issue 号并在当阶段清零。
3. 每个回归包必须能追溯到 FR/NFR 编号（AGENTS.md 质量门槛）。
4. P05 之后记录性能基线；后续回归中主要 CRUD 接口 P95 超过 500 ms 或较基线劣化 50% 以上，报 P1。

## 3. 治理自检（R-GOV）

这些测试的失败意义高于普通测试：**它们验证的是门禁本身是否还在生效**。任何 R-GOV 失败按 P0 处理。

| 编号 | 验证内容 | 关联需求 | 落地阶段 |
| --- | --- | --- | --- |
| R-GOV-01 | 解析 Checkstyle/ESLint 配置，断言文件长度、函数长度、复杂度、参数、嵌套阈值与 `docs/coding-standards.md` §7 **完全一致**；文档改阈值不同步配置（或反向）即失败 | NFR-MAINT-01 | P01 |
| R-GOV-02 | ArchUnit/import 规则：无循环依赖、无跨模块 `infrastructure` 引用、未标注 `@PublicApi`/`@ExperimentalApi` 的类不被跨模块引用 | NFR-MAINT-01 | P01 骨架，P02 起生效 |
| R-GOV-03 | 扩展点闭环：代码中 `ServiceKey`/`ExtensionPoint`/`DomainEventType` 常量集合 == `docs/extension-points.md` active 集合；每个 active 扩展点有对应注册-撤销测试；发现未登记常量即失败 | NFR-MAINT-02 | P02 |
| R-GOV-04 | 状态镜像一致：`STATUS.md` 锚点（stage/status/nextAction）与 `docs/project-status.json` 完全一致 | STATUS 更新规则 | P01 |
| R-GOV-05 | 文档完整：全部 Markdown 相对链接可解析；`README.md` 与 `docs/05-documentation-guide.md` 索引覆盖所有长期文档 | 文档维护指南 | P01 |
| R-GOV-06 | 门禁防失效（fail-open guard）：`tests/fixtures/violations/` 存放**故意违规**样例（超行数文件、非法跨模块引用）；`scripts/check-repo-health` 对违规样例运行 lint/依赖检查并断言"必须失败且命中预期规则"；若违规样例意外通过，说明门禁被放宽 | NFR-MAINT-01 | P01 |
| R-GOV-07 | 契约 fixture 回归：`plugin.json` 与 `RequirementSpec` 的合法/非法 fixture 目录在 L2/L3 全部重放，结果稳定 | FR-PLUGIN-01/08、FR-ISSUE-04 | P07、P10 |
| R-GOV-08 | 日志脱敏：测试输出与固定日志 fixture 中不出现密码、JWT、API key、完整 Authorization 头 | NFR-SEC 系列 | P02 |
| R-GOV-09 | 骨架纯净性：空库执行平台迁移后，系统没有任何业务菜单、实体或页面；`plugins/example-inventory` 是可通过标准 API 安装/停用/卸载的普通 Level 1 包，不存在硬编码开关或内置路径 | NFR-SKEL-01、FR-DEMO-03 | P09 |

## 4. 业务回归包（按阶段交付）

每个阶段在退出条件中并入下表对应回归包；上游回归包永久保留、每次 L3 全量执行。

| 包 ID | 内容 | 关联需求 | 交付阶段 |
| --- | --- | --- | --- |
| RB-AUTH | 登录/退出、过期与无效 JWT、三类角色权限矩阵、越权访问 | FR-AUTH-01/02/03 | P03 |
| RB-META | 实体/字段/视图配置、字段名与类型白名单、`FieldTypeRegistry` 单点断言、元数据权限过滤 | FR-META-01..05 | P04 |
| RB-DATA | 动态 CRUD 全路径、参数绑定与注入防护、字段校验、实体业务规则、性能基线记录 | FR-META-04/05、NFR-SEC-02 | P05 |
| RB-UI | renderer registry 映射、菜单注册/撤销、loading/empty/error/denied/stale 状态 | FR-META-04、NFR-UX-01 | P06 |
| RB-PLUGIN-VALID | 包校验正反例、schemaVersion 分派、依赖解析、幂等导入、路径穿越与脚本资源拒绝 | FR-PLUGIN-01..08 | P07 |
| RB-PLUGIN-LIFE | 生命周期状态机、迁移失败回滚、注册冲突、stale activation、重启恢复、卸载清理（`docs/07` §5 八例必测）；预置 example-inventory 与第三方插件同权：导入→安装→停用→卸载无残留 | FR-PLUGIN-02..07、FR-DEMO-03、NFR-PLUGIN-01 | P08 |
| RB-ISSUE | Issue 状态机合法/非法迁移、规格 Schema 版本审计、退回与关闭原因 | FR-ISSUE-01..06 | P10 |
| RB-AI | 固定 fixture 生成、输出 Schema 校验、重试上限、非法 JSON/越权 renderer 负例、无模型手工兜底 | FR-ISSUE-03..06 | P11 |
| RB-E2E | 三条端到端冒烟：动态实体 CRUD、插件生命周期、Issue→规格→骨架→审核→安装；含骨架纯净性（NFR-SKEL-01）与演示库存卸载干净断言 | 场景 A/B/B2/C/D | P12 起 |

## 5. 回归数据与 fixture 管理

目录约定：

```text
tests/fixtures/
├── violations/            R-GOV-06 故意违规样例（不参与正常构建）
├── plugins/valid/         合法插件包（每个含内容哈希）
├── plugins/invalid/       非法插件包（按错误码命名，如 invalid_manifest_x.json）
├── ai/                    固定提示词、固定模型响应、负例响应
├── meta/                  实体/字段/视图 golden 配置
└── fixtures.json          条目清单与 SHA-256
```

规则：

1. 修改 fixture 必须同步 `fixtures.json` 哈希，并在 PR 说明原因；禁止为了测试变绿修改 golden 数据。
2. golden 快照只存稳定契约字段；`requestId`、时间戳等随机值先在断言前规范化。
3. 插件 fixture 一律为 Level 1 声明式资源，禁止放入真实密钥或个人信息。

## 6. 失败处理

- 与 `docs/04-test-strategy.md` 缺陷等级联动；R-GOV 失败 = P0，RB 包失败按影响面定 P0/P1。
- 阶段退出时必须在 `STATUS.md` 进度日志的证据列附回归运行结果摘要。
- 回归失败不允许通过"改测试数据绕过"处理；先修代码或先修测试的决策必须在 PR 说明中写明。

## 7. 落地检查清单

- P01：交付 `tests/`、`tests/fixtures/`、`scripts/check-repo-health`，R-GOV-01..06 可运行。
- P02 起：每阶段退出前把对应 RB 包并入 L2/L3 并全绿。
- P12：RB-E2E 在干净数据库连续三次通过。
- P14：按 §2 L4 完成发布候选全量回归并记录证据。

## 8. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-08-20 | v1.0 基线：回归分层、R-GOV 治理自检、业务回归包与 fixture 规则 |
