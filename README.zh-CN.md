# FlexForge

[![CI](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml/badge.svg)](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)

[English](./README.md) | **简体中文**

FlexForge 是一个**元数据驱动、插件可扩展的数据管理平台**：业务实体只需描述一次，平台即在运行时生成完整的增删改查界面；元数据、视图、主题、语言包与数据处理器均可打包为声明式插件；借助 AI 辅助澄清管线，把原始需求转化为可安装的插件骨架。

项目源于毕业设计，但按生产级工程纪律构建——21 项仓库健康门禁、每个扩展点都有契约测试、每个权限/迁移/插件操作都有失败路径测试。

![工作台](docs/assets/screenshot-workbench.png)

## 核心能力

- **元数据驱动动态 CRUD**——在数据模型页（或随插件）定义实体与字段，列表/新建/编辑/详情页、表格与看板视图、字段校验、CSV/XLSX 导出全部在运行时生成，无需为每个实体写页面代码。
- **声明式插件运行时**——Level 1 包只声明资源（导航/实体/视图/迁移/主题/语言包/预设）；Level 2 包可额外携带沙箱化 Python 数据处理器（表格 → 表格/图表/文件产物）。同版本不可变、激活带依赖检查、卸载可撤销。
- **Issue → AI → 插件管线**——用户在对话式工作台描述需求，AI（默认离线 fixture，或任意 OpenAI 兼容接口如 DeepSeek）将其澄清为经 Schema 校验的结构化规格；开发者预览、审核并生成可安装的插件骨架。
- **主题与语言也是插件**——骨架保持中性；默认与暖色两套主题以 Level 1 包交付，界面内置中文基线并提供英/日/法/西四个语言包（各 352 键），停用即回退基线。
- **管理面**——基于角色的访问（ADMIN/DEVELOPER/USER）、支持批量停启用的用户管理、可过滤的审计日志、插件预设（保存并一键恢复激活组合）、内嵌图表与文件工具。

![插件管理](docs/assets/screenshot-plugins.png)

## 快速启动（Docker）

前置：Docker Desktop（含 Compose）。宿主端口默认 `8088`（API）与 `5173`（UI），仅绑定 `127.0.0.1`。

```bash
cp .env.example .env          # 设置 AUTH_JWT_SECRET 与引导管理员口令
docker compose up -d --build  # PostgreSQL 17 + 后端 + 前端

curl http://127.0.0.1:8088/actuator/health   # 期望 {"status":"UP"}
# 打开 http://127.0.0.1:5173
```

从 [`plugins/`](./plugins) 目录导入示例插件（业务示例、主题、语言包）：把打包的 zip 拖到**插件管理**页的上传区即可，随后打开开关。界面语言在**设置**页切换。

无 Docker 直跑：`cd backend && ./mvnw -pl flexforge-app -am spring-boot:run`，另开 `cd frontend && VITE_PROXY_TARGET=http://127.0.0.1:8088 npm run dev`。

## 架构

模块化单体：一个可部署的 Spring Boot 应用 + 一个 Vue 3 SPA + 一个数据库，内部模块边界与依赖方向受治理规则约束（docs/10）。

```
backend/
  flexforge-common    # 跨模块 API（审计端口、PublicApi 标记、错误口径）
  flexforge-auth      # JWT 认证、角色、用户管理、批量操作
  flexforge-meta      # 元数据实体/字段/视图 + 动态记录存储
  flexforge-data      # JSONB 记录读写，参数化 + 白名单
  flexforge-plugin    # 包导入/校验、激活生命周期、预设
  flexforge-runtime   # 面向前端的资产注册（签名 URL、主题、语言包）
  flexforge-issue     # Issue 工作流、AI 澄清、规格修订、发布
  flexforge-ai        # 模型端口（fixture/http）、提示词、规格 Schema、生成器
  flexforge-app       # 装配：控制器、过滤器、异常映射、集成测试
frontend/
  src/registry/       # 菜单/渲染器/主题/语言/布局/记录动作 registries
  src/components/     # 渲染器、工作台、Issue 工作台、插件与管理界面
  src/views/          # 路由视图（工作台/数据模型/插件/用户/审计/文件工具…）
plugins/              # Level 1/2 包：业务示例、主题、语言包
database/migrations/  # Flyway 基线（V001…）——插件自带各自迁移
```

所有扩展面（导航、实体、视图、渲染器、主题资产、语言包、记录动作、数据处理器）均登记于[扩展点登记册](./docs/extension-points.md)并在导入时校验，未知贡献一律拒绝。

## 安全与质量

- 安全基线 [docs/13](./docs/13-security-baseline.md)：输入全量校验、动态 SQL 参数化且标识符仅白名单、插件包全链路校验（Schema/路径/大小/类型）、Level 2 处理器运行于锁定子进程（清空环境变量、字节上限、硬超时）、密钥仅经环境变量/加密存储、特权操作全审计。
- AI 配置只有在上游探活（`GET {base}/models`）通过后才落库；出站 URL 拒绝环回/私有/保留地址。
- 一条命令跑完本地全量门禁（格式、lint、类型检查、单元与集成测试、构建、治理规则），与 CI 同一入口：

```bash
node scripts/check-repo-health.mjs   # 21 项检查
```

- CI（GitHub Actions）六项：密钥扫描、仓库健康、后端构建+测试（Testcontainers）、前端构建+测试、依赖审计、Docker 镜像构建。
- 后端测试经 Testcontainers 跑在真实 PostgreSQL 上——覆盖每条插件生命周期失败路径。

## 当前状态

规划的全部 27 个阶段（P00–P26）已完成，项目处于 `release_candidate` 维护期。阶段历史与当前锚点见 [STATUS.md](./STATUS.md)，各阶段验收标准见 [docs/09](./docs/09-detailed-implementation-plan.md)。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [STATUS.md](./STATUS.md) | 当前阶段锚点与进度日志 |
| [docs/00](./docs/00-feasibility-review.md) · [01](./docs/01-project-plan.md) · [09](./docs/09-detailed-implementation-plan.md) | 可行性评审、项目计划书、分阶段实施计划 |
| [docs/02](./docs/02-requirements.md) · [03](./docs/03-architecture.md) · [04](./docs/04-test-strategy.md) | 需求规格（FR/NFR）、总体架构、测试策略 |
| [docs/07](./docs/07-plugin-runtime-data-model.md) · [docs/extension-points.md](./docs/extension-points.md) | 插件运行时数据模型与扩展点登记册 |
| [docs/10](./docs/10-engineering-governance.md) · [docs/13](./docs/13-security-baseline.md) | 工程治理与安全基线 |
| [docs/11](./docs/11-regression-test-plan.md) · [docs/12](./docs/12-thesis-experiment-plan.md) | 回归测试计划、论文实验计划 |
| [docs/project-index.md](./docs/project-index.md) · [docs/05](./docs/05-documentation-guide.md) · [docs/repository-maintenance.md](./docs/repository-maintenance.md) · [docs/coding-standards.md](./docs/coding-standards.md) | 文件级索引、文档指南、仓库维护约束、编码规范 |
| [ADR 0001–0005](./docs/adr/0001-mvp-architecture.md) | 架构决策记录 |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | 贡献指南 |

## MVP 一句话定义

管理员定义一个业务实体及字段，系统自动生成可用的增删改查页面；开发者将一组元数据和配置打包成插件；用户通过 Issue 提交需求，由 AI 整理为结构化规格并生成插件骨架——经人工审核后安装激活。

## 非目标

不做完整 ERP、通用 BPMN、开放插件市场、多租户 SaaS、任意 JS/Java 沙箱和无人值守生产发布——这些刻意排除在范围之外，保留为未来方向。
