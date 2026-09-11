<div align="center">

# FlexForge

**元数据驱动、插件可扩展的数据管理平台。**

数据模型只定义一次——增删改查页面、工作流与看板随之而生。
一切皆可插件扩展：实体、视图、主题、语言包、数据处理器。

[![CI](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml/badge.svg)](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml)
[![Vue 3](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white)](https://vite.dev/)
[![Spring Boot](https://img.shields.io/badge/Spring_0Boot-3-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java 17](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Flyway](https://img.shields.io/badge/Flyway-CC0200?logo=flyway&logoColor=white)](https://flywaydb.org/)
[![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)
[![Vitest](https://img.shields.io/badge/Vitest-6E9F18?logo=vitest&logoColor=white)](https://vitest.dev/)
[![Testcontainers](https://img.shields.io/badge/Testcontainers-1EC66F?logo=testcontainers&logoColor=white)](https://testcontainers.com/)

[快速开始](#快速开始) · [核心特性](#核心特性) · [界面截图](#界面截图) · [技术栈](#技术栈) · [架构](#架构) · [文档](#文档)

[English](./README.md) | **简体中文**

</div>

---

## FlexForge 是什么？

FlexForge 让小团队无需手写页面即可运转业务数据：

- **管理员**在数据模型页定义实体与字段——列表/新建/编辑/详情页、表格与看板视图、字段校验、CSV/XLSX 导出全部在运行时生成。
- **开发者**把元数据、视图、主题、语言包和 Python 数据处理器打包为带版本的插件，激活带依赖检查、卸载可回滚。
- **用户**在对话式工作台描述需求，AI 助手（离线 fixture，或任意 OpenAI 兼容接口如 DeepSeek）将其整理为经过校验的结构化规格，再生成可审核的插件骨架。

平台自身保持中性：主题与语言同样以插件交付；界面语言（中文基线之上的英/日/法/西）也只是一个个可开关的包。

## 界面截图

| 工作台 | 插件管理 |
|:---:|:---:|
| ![工作台](docs/assets/screenshot-workbench.png) | ![插件管理](docs/assets/screenshot-plugins.png) |

## 核心特性

- 🧩 **声明式插件**——Level 1 包只声明资源（导航/实体/视图/迁移/主题/语言包/预设）；Level 2 包可携带沙箱化 Python 数据处理器（表格 → 表格/图表/文件产物）。
- 🗂 **元数据驱动 CRUD**——每个实体的页面在运行时生成，零页面代码；表格与看板双呈现；基于 JSONB 的分页查询，SQL 参数化且标识符仅白名单。
- 🤖 **AI 需求管线**——对话式澄清 → Schema 校验的结构化规格 → 插件骨架预览与生成；设计上坚持人工审核后才激活。
- 🎨 **主题与多语言即插件**——默认/暖色两套主题；中文基线之上四个语言包（各 352 键），停用自动回退。
- 🔐 **安全优先**——JWT + 角色访问（ADMIN/DEVELOPER/USER）、全量审计日志、用户批量操作、插件包导入全链路校验、处理器子进程沙箱（清空环境变量/字节上限/硬超时）。
- 🩺 **上游感知的 AI 配置**——模型端点保存前探活（`GET {base}/models`）；出站 URL 拒绝环回/私有/保留地址。
- 📊 **图表与文件工具**——令牌化调色的 Chart.js 渲染与文件进出处理器，独立工具页呈现。

## 快速开始

### Docker 一键启动

```bash
cp .env.example .env          # 设置 AUTH_JWT_SECRET 与引导管理员口令
docker compose up -d --build  # PostgreSQL 17 + 后端 + 前端

curl http://127.0.0.1:8088/actuator/health   # 期望 {"status":"UP"}
```

打开 **http://127.0.0.1:5173**，用引导管理员登录，再把 [`plugins/`](./plugins) 目录下的插件包拖入**插件管理**页导入示例应用、主题与语言包。

### 本地开发

```bash
# 后端（Spring Boot，默认 8080）
cd backend && ./mvnw -pl flexforge-app -am spring-boot:run

# 前端（Vite 开发服务器，代理到后端）
cd frontend && npm ci
VITE_PROXY_TARGET=http://127.0.0.1:8080 npm run dev
```

### 测试与检查

```bash
cd backend && ./mvnw verify                 # 单元 + Testcontainers 集成测试
cd frontend && npm run test                # Vitest 组件/单元测试
node scripts/check-repo-health.mjs          # 本地全量门禁，与 CI 同一入口
```

## 技术栈

| 层 | 选型 |
| --- | --- |
| 前端 | Vue 3.5 · TypeScript 5.9 · Vite 8 · Vue Router · Chart.js 4 · 字体自托管（无 CDN） |
| 后端 | Java 17 · Spring Boot 3 · Spring Security（JWT）· JdbcTemplate · Flyway |
| 数据库 | PostgreSQL 17（JSONB 动态记录存储） |
| 插件运行时 | 声明式 JSON manifest · zip 包 · Python 3 子进程沙箱（Level 2） |
| 质量 | Vitest · JUnit 5 · Testcontainers · ESLint/Prettier · Checkstyle · GitHub Actions |
| 交付 | Docker Compose · GitHub Actions 镜像构建 |

## 架构

模块化单体——一个可部署的 Spring Boot 应用 + 一个 Vue 3 SPA + 一个数据库，内部模块边界严格：

```
backend/
  flexforge-common    跨模块 API（审计端口、错误口径）
  flexforge-auth      JWT 认证、角色、用户管理、批量操作
  flexforge-meta      元数据实体/字段/视图
  flexforge-data      JSONB 记录读写（参数化 + 白名单）
  flexforge-plugin    包导入/校验、激活生命周期、预设
  flexforge-runtime   面向前端的资产注册（签名 URL、主题、语言包）
  flexforge-issue     Issue 工作流、AI 澄清、规格修订
  flexforge-ai        模型端口（fixture/http）、提示词、规格 Schema、生成器
  flexforge-app       装配：控制器、过滤器、异常映射

frontend/
  src/registry/       菜单/渲染器/主题/语言/布局/记录动作 registries
  src/components/     渲染器、工作台、Issue 工作台、插件与管理界面
  src/views/          路由视图（工作台/数据模型/插件/用户/审计/文件工具…）

plugins/              业务示例、主题、语言包（Level 1/2）
database/migrations/  Flyway 基线——插件自带各自迁移
```

所有扩展面均登记于[扩展点登记册](./docs/extension-points.md)并在导入时校验，未知贡献一律拒绝。

## 当前状态

初步开发完成（`v0.1.0`）——规划功能全部交付且有测试覆盖，维护中。设计细节见[文档](#文档)，开发过程记录见 [STATUS.md](./STATUS.md)。

## 路线图

- 暗色主题与主题编辑器
- 面向第三方作者的插件包格式文档
- 更多语言包与社区翻译
- 部署加固指南（TLS、外置 PostgreSQL）

## 文档

- [需求规格](./docs/02-requirements.md) · [总体架构](./docs/03-architecture.md) · [测试策略](./docs/04-test-strategy.md)
- [插件运行时数据模型](./docs/07-plugin-runtime-data-model.md) · [扩展点登记册](./docs/extension-points.md)
- [安全基线](./docs/13-security-baseline.md) · [工程治理](./docs/10-engineering-governance.md)
- [项目计划](./docs/01-project-plan.md) · [实施阶段](./docs/09-detailed-implementation-plan.md) · [开发日志](./STATUS.md)
- [贡献指南](./CONTRIBUTING.md)

---

<p align="center">Built with Vue · Spring Boot · PostgreSQL</p>
