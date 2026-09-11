# FlexForge

[![CI](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml/badge.svg)](https://github.com/KuroNeko-night/FlexForge/actions/workflows/ci.yml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-4FC08D?logo=vuedotjs&logoColor=white)](https://vuejs.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)

**English** | [简体中文](./README.zh-CN.md)

FlexForge is a **metadata-driven, plugin-extensible data management platform**: describe a business entity once, and the platform generates its full CRUD surface at runtime; package metadata, views, themes, locales and data processors as declarative plugins; turn raw requirements into installable plugin skeletons through an AI-assisted clarification pipeline.

Born as a graduation design project, it is built with production-shaped engineering discipline — a 21-check repository health gate, contract tests on every extension point, and failure-path tests on every permission, migration and plugin operation.

![Workbench](docs/assets/screenshot-workbench.png)

## Highlights

- **Metadata-driven dynamic CRUD** — define entities and fields in the Data Model page (or ship them with a plugin); list / create / edit / detail pages, table & kanban views, validation, CSV/XLSX export are generated at runtime. No page code per entity.
- **Declarative plugin runtime** — Level 1 packages declare resources only (navigation, entities, views, migrations, themes, locales, presets); Level 2 packages may additionally ship sandboxed Python data processors (table → table / chart / file artifacts). Same-version immutability, dependency-checked activation, reversible uninstall.
- **Issue → AI → plugin pipeline** — users describe a need in a chat workspace; the AI (offline fixture by default, or any OpenAI-compatible API such as DeepSeek) clarifies it into a structured, schema-validated spec; developers review, preview and generate an installable plugin skeleton.
- **Themes & languages are plugins too** — the skeleton stays neutral; a default and a warm theme ship as Level 1 packages, and the UI ships with a Chinese baseline plus English / Japanese / French / Spanish language packs (352 keys each). Disabling a pack falls back to the baseline.
- **Admin plane** — role-based access (ADMIN / DEVELOPER / USER), user management with batch block/unblock, a filterable audit log, plugin presets (save & restore activation sets), inline charts and file tools.

![Plugins](docs/assets/screenshot-plugins.png)

## Quick Start (Docker)

Prerequisites: Docker Desktop (with Compose). Ports default to `8088` (API) and `5173` (UI), bound to `127.0.0.1` only.

```bash
cp .env.example .env          # set AUTH_JWT_SECRET and a bootstrap admin password
docker compose up -d --build  # PostgreSQL 17 + backend + frontend

curl http://127.0.0.1:8088/actuator/health   # expect {"status":"UP"}
# open http://127.0.0.1:5173
```

Import the demo plugins (business examples, themes, language packs) from the [`plugins/`](./plugins) directory by dragging the zipped package (or the directory contents zipped) onto the upload area of the **Plugins** page, then toggle them on. Switch the interface language in **Settings**.

Without Docker: `cd backend && ./mvnw -pl flexforge-app -am spring-boot:run` and `cd frontend && VITE_PROXY_TARGET=http://127.0.0.1:8088 npm run dev`.

## Architecture

Modular monolith: one deployable Spring Boot app, one Vue 3 SPA, one database — with strict internal module boundaries and dependency-direction rules (docs/10).

```
backend/
  flexforge-common    # cross-cutting API (audit port, PublicApi marker, errors)
  flexforge-auth      # JWT auth, roles, user management, batch ops
  flexforge-meta      # metadata entities/fields/views + dynamic record storage
  flexforge-data      # record query/write over JSONB, parameterized & whitelisted
  flexforge-plugin    # package import/validation, activation lifecycle, presets
  flexforge-runtime   # frontend-facing asset registry (signed URLs, themes, locales)
  flexforge-issue     # issue workflow, AI clarification, spec revisions, publish
  flexforge-ai        # model ports (fixture/http), prompts, spec schema, generator
  flexforge-app       # assembly: controllers, filters, exception mapping, tests
frontend/
  src/registry/       # menu / renderer / theme / locale / layout / record-action registries
  src/components/     # renderers, workbench, issue workspace, plugin & admin UI
  src/views/          # route views (workbench, data model, plugins, users, audit, tools…)
plugins/              # Level 1/2 packages: example business apps, themes, locales
database/migrations/  # Flyway baseline (V001…) — plugins carry their own migrations
```

Every extension surface (navigation, entity, view, renderer, theme asset, locale, record action, data processor) is registered in [docs/extension-points.md](./docs/extension-points.md) and validated at import; unknown contributions are rejected.

## Security & Quality

- Security baseline [docs/13](./docs/13-security-baseline.md): input validation everywhere, parameterized dynamic SQL with whitelist-only identifiers, plugin packages fully validated (schema/path/size/kind), Level 2 processors run in a locked-down subprocess (env stripped, byte caps, hard timeouts), secrets only via environment / encrypted storage, audit on every privileged operation.
- AI configuration is saved only after the upstream passes a reachability probe (`GET {base}/models`); outbound URLs are guarded against loopback/private/reserved addresses.
- One command runs the full local gate (format, lint, type-check, unit & integration tests, build, governance rules) — the same entry point CI uses:

```bash
node scripts/check-repo-health.mjs   # 21 checks
```

- CI (GitHub Actions): secret scan, repo health, backend build+tests (Testcontainers), frontend build+tests, dependency audit, Docker image build.
- Backend tests run against a real PostgreSQL via Testcontainers — including every plugin lifecycle failure path.

## Status

All 27 planned stages (P00–P26) are complete; the project is in `release_candidate` maintenance. Stage history and the current anchor live in [STATUS.md](./STATUS.md); the per-stage acceptance criteria in [docs/09](./docs/09-detailed-implementation-plan.md).

## Documentation

| Doc | Contents |
| --- | --- |
| [STATUS.md](./STATUS.md) | Current stage anchor & progress log |
| [docs/00](./docs/00-feasibility-review.md) · [01](./docs/01-project-plan.md) · [09](./docs/09-detailed-implementation-plan.md) | Feasibility review, project plan, staged implementation plan |
| [docs/02](./docs/02-requirements.md) · [03](./docs/03-architecture.md) · [04](./docs/04-test-strategy.md) | Requirements (FR/NFR), architecture, test strategy |
| [docs/07](./docs/07-plugin-runtime-data-model.md) · [docs/extension-points.md](./docs/extension-points.md) | Plugin runtime data model & extension-point registry |
| [docs/10](./docs/10-engineering-governance.md) · [docs/13](./docs/13-security-baseline.md) | Engineering governance & security baseline |
| [docs/11](./docs/11-regression-test-plan.md) · [docs/12](./docs/12-thesis-experiment-plan.md) | Regression plan, thesis experiment plan |
| [docs/project-index.md](./docs/project-index.md) · [docs/05](./docs/05-documentation-guide.md) · [docs/repository-maintenance.md](./docs/repository-maintenance.md) · [docs/coding-standards.md](./docs/coding-standards.md) | File-level index, doc guide, repo rules, coding standards |
| [ADR 0001–0005](./docs/adr/0001-mvp-architecture.md) | Architecture decision records |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | How to contribute |

## MVP in One Sentence

An administrator defines a business entity and its fields, and the system generates working CRUD pages; a developer packages metadata and configuration as a plugin; a user files a requirement as an Issue, which the AI shapes into a structured spec and an installable plugin skeleton — reviewed by a human before activation.

## Non-Goals

No full ERP, no general-purpose BPMN, no open plugin marketplace, no multi-tenant SaaS, no arbitrary JS/Java sandbox, no unattended production releases — deliberately out of scope for this project, kept as future directions.
