# FlexForge

[![CI](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml/badge.svg)](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml)

FlexForge 是一个面向毕业设计验证的模块化数据管理系统。项目以元数据驱动和插件化为主线，辅以轻量工作流与 AI 需求澄清/代码骨架生成，目标是验证“需求规格 -> 可配置业务模块 -> 测试发布”的闭环是否可行。

## 快速启动（开发环境）

前置：JDK 17+、Node.js LTS、Docker Desktop（含 Compose）。

```bash
# 1. 一键启动 PostgreSQL + 后端 + 前端（首次构建需数分钟拉取依赖）
docker compose up -d --build

# 2. 健康检查
curl http://127.0.0.1:8088/actuator/health   # 后端，期望 {"status":"UP"}
# 前端：浏览器打开 http://127.0.0.1:5173 ，页面显示“后端服务正常（UP）”

# 3. 宿主端口默认 8088/5173（避免与本机其他服务冲突），可复制 .env.example 为 .env 覆盖
#    BACKEND_PORT / FRONTEND_PORT

# 4. 停止（数据保留在 flexforge-pgdata 卷）
docker compose down
```

> 安全基线（docs/13 §3.8）：前后端走 Vite 同源代理，后端不开 CORS 通配；所有端口只绑定 `127.0.0.1`。

后端源码构建与测试（Maven 由 Wrapper 锁定为 3.9.16，无需本机安装）：

```bash
cd backend
.\mvnw.cmd verify   # PowerShell；Git Bash 可用 ./mvnw。含 Testcontainers 启动冒烟（需 Docker）
```

本机直跑后端（默认 8080 端口）：复制 `.env.example` 为 `.env` 或导出同名环境变量后执行 `.\mvnw.cmd -pl flexforge-app spring-boot:run`（Git Bash 用 `./mvnw`）。

前端源码构建与测试：

```bash
cd frontend
npm ci
npm run dev          # Vite 开发服务器；代理目标默认 http://127.0.0.1:8080
npm run build        # type-check + 生产构建（preview 模式启用 CSP/安全响应头骨架）
```

本机直跑前端但后端在 Compose 里时，先导出 `VITE_PROXY_TARGET=http://127.0.0.1:8088` 再执行 `npm run dev`。

仓库健康检查（本地与 CI 同一入口，一条命令完成格式、lint、测试、构建与 R-GOV 门禁）：

```bash
node scripts/check-repo-health.mjs
```

## 当前状态

- 阶段：P01 仓库与工程骨架（in_progress，迭代 1 后端骨架已完成）
- 版本：`0.1.0-SNAPSHOT`
- 代码仓库：GitHub 私有仓库 [KuroNeko-night/FlexForge](https://github.com/KuroNeko-night/FlexForge)（`main` 分支）
- 当前结论：技术上可行，但必须以 MVP 为边界，采用模块化单体和轻量运行时插件模型，不在毕业设计阶段实现通用企业级平台。
- 原始概念稿：[FlexForge.md](./FlexForge.md)

## 文档索引

- [可行性评审](./docs/00-feasibility-review.md)
- [项目计划书](./docs/01-project-plan.md)
- [详细实施阶段计划](./docs/09-detailed-implementation-plan.md)
- [工程质量与可扩展性治理基线](./docs/10-engineering-governance.md)
- [回归测试计划](./docs/11-regression-test-plan.md)
- [论文实验与数据收集计划](./docs/12-thesis-experiment-plan.md)
- [安全基线](./docs/13-security-baseline.md)
- [项目索引（文件级定位）](./docs/project-index.md)
- [扩展点登记册](./docs/extension-points.md)
- [当前开发状态](./STATUS.md)
- [MVP 需求规格](./docs/02-requirements.md)
- [总体架构](./docs/03-architecture.md)
- [测试策略](./docs/04-test-strategy.md)
- [文档维护指南](./docs/05-documentation-guide.md)
- [dsh 插件化学习记录](./docs/06-dsh-reference-study.md)
- [插件运行时数据模型](./docs/07-plugin-runtime-data-model.md)
- [仓库维护约束](./docs/repository-maintenance.md)
- [编码与注释规范](./docs/coding-standards.md)
- [贡献指南](./CONTRIBUTING.md)
- [Agent 约束](./AGENTS.md)

## MVP 一句话定义

管理员可以定义一个业务实体及字段，系统自动生成可用的增删改查页面；开发者可以将一组元数据和配置打包成插件；用户可以通过 Issue 提交需求，由 AI 将需求整理为结构化规格并生成插件骨架，经过人工审核后安装到测试环境。

## 非目标

毕业设计阶段不追求完整 ERP、通用 BPMN、开放插件生态、多租户 SaaS、任意 JavaScript/Java 代码沙箱和无人值守生产发布。这些内容保留为扩展方向，不作为答辩前的交付承诺。

## 开发原则

1. 先证明闭环，再扩展能力。
2. 业务规则优先结构化配置，AI 只生成建议和骨架。
3. 核心路径必须可人工操作、可测试、可回滚。
4. 每项功能都有需求编号、验收标准和测试记录。
5. 系统骨架不含业务；演示库存也只以可停用/卸载的普通插件交付。
