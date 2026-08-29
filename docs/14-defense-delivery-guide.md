# 14 答辩交付运行手册（P14）

> 本文档是答辩演示的唯一运行手册：环境启动、账号、演示流程、离线三保险与故障排查。
> 安全基线见 `docs/13`；已知限制与论文素材见 `docs/00` §7；架构图与分层见 `docs/03` §2/§5。

## 1. 演示环境启动

```bash
cp .env.example .env   # 按注释填 AUTH_JWT_SECRET（openssl rand -base64 48）与演示口令
docker compose up -d --build
# 健康：curl http://127.0.0.1:8088/actuator/health → {"status":"UP"}；前端 http://127.0.0.1:5173
```

- 端口绑定仅 127.0.0.1（docs/13 §3.8）：后端 `${BACKEND_PORT:-8088}`、前端 `${FRONTEND_PORT:-5173}`、数据库 5432。
- 首次启动空库自动执行平台迁移并引导管理员（`FLEXFORGE_BOOTSTRAP_ADMIN_PASSWORD`，仅空库时生效）。
- 模型默认 fixture（`FLEXFORGE_AI_PROVIDER=fixture`），答辩演示不依赖任何外部模型服务。

## 2. 演示账号（三角色）

| 角色 | 用户名 | 用途 | 来源 |
| --- | --- | --- | --- |
| ADMIN | `admin` | 插件导入/启停/卸载、插件管理页 | compose 引导（`.env`） |
| DEVELOPER | `demo-developer` | 建实体、Issue 审核/生成 | `demo-e2e.sh` 首跑经系统用户 API 创建（幂等） |
| USER | `demo-user` | 业务 CRUD、提 Issue | 同上 |

口令均经 `.env` 注入（`FLEXFORGE_ADMIN_PASS` 须与引导口令一致；演示账号口令变量见 `.env.example`）；不入仓库、不入论文。

## 3. 演示流程

### 3.1 一键端到端（推荐开场，~1 分钟）

```bash
export PYTHONUTF8=1   # Windows 原生 Python 必需：管道 stdin 缺省走 ANSI 代码页，中文载荷会解码失败
FLEXFORGE_BASE_URL=http://127.0.0.1:8088 E2E_DEMO_TIMING_FILE=/tmp/e2e-demo-timing.csv \
  bash scripts/demo-e2e.sh
```

> 端口要点：compose 后端宿主端口默认 **8088**（脚本缺省 8080，与 compose 默认不一致，须显式指定 `FLEXFORGE_BASE_URL` 或在 `.env` 固化；若提示"登录失败"先查端口而非口令）。五场景单脚本串联（A 动态实体 CRUD / B 插件生命周期 / B2 失败与过期激活 / C Issue→AI 澄清→批准→生成→安装→DONE / D 骨架纯净性），逐环节输出 `E2E-DEMO,stage,ms` 计时行并可留档 CSV。前置：干净库（插件清单非空即中止并提示 `docker compose down -v` 重置）。

### 3.2 分场景人工演示（配合前端界面）

1. **登录**：5173 → 三角色分别登录，展示工作台与菜单差异（插件管理仅 ADMIN 可见）。
2. **场景 A**：开发者建"物料"实体（四字段）→ 启用 → 普通用户增改查（`docs/02` §4-A）。
3. **场景 B**：ADMIN 装库存插件（`scripts/demo-example-inventory.sh` 或插件管理链路）→ 用户 CRUD+非负规则 → 停用/启用/卸载。
4. **场景 C**：用户提 Issue → 开发者 clarify 两轮 → 批准（此前生成被拒=人工审核门）→ 生成 → 用户用生成实体 → TESTED→DONE；插件管理页看版本/激活尝试/失败诊断。
5. **场景 D**：卸载后菜单/实体/注册无残留；干净库启动无任何业务功能（骨架纯净性）。

### 3.3 论文数据导出

```bash
node scripts/export-thesis-data.mjs
```

四数据面 CSV（AI 任务日志/规格版本/激活结局/迁移记录，docs/12 §3）；存档不进仓库，路径记入进度日志。

## 4. 离线答辩三保险（docs/09 P14）

1. **镜像预导入**：演示机提前 `docker compose build` + `docker compose pull`（postgres:17-alpine），现场不依赖网络：`docker compose up -d` 即可。
2. **fixture 模式**：模型演示固定走 `FLEXFORGE_AI_PROVIDER=fixture`（离线可用、行为确定）；在线模型仅作网络可用时的增强展示，不在主线流程。
3. **录屏兜底**：按 §3.2 顺序录全流程一段（含计时行），现场故障时播放。建议另录 RB-E2E CI 全绿片段作辅助证据。

## 5. 故障排查手册

| 症状 | 原因 | 处置 |
| --- | --- | --- |
| `docker compose up` 直接报 `AUTH_JWT_SECRET`，或 backend 日志含 `flexforge.auth.jwt-secret` | `.env` 缺失/为空/过短（compose 层 fail-fast，S4） | 按第 1 节补 `.env`（≥256bit）后 `docker compose up -d` |
| 前端只显示"后端服务正常"壳或旧界面 | 容器镜像过期（未 `--build`） | `docker compose up -d --build` 重建 |
| 演示脚本提示"登录失败"（curl 静默） | 多为端口：脚本缺省 8080，compose 默认 8088 | 显式 `FLEXFORGE_BASE_URL=http://127.0.0.1:8088`；再核对口令（管理员须与引导口令同值） |
| 演示脚本 `插件清单非空` 中止 | 库内已有插件（重复演示） | `docker compose down -v && docker compose up -d` 重置后重跑 |
| clarify/generate 503 `model_unavailable` | provider=http 且模型不可达 | 切回 fixture（默认）；或走手工规格兜底（PUT /spec） |
| 插件激活 400 `dependency_missing` | 依赖未导入/未激活 | 先导入并激活依赖，或按插件页失败诊断处理 |
| 旧 activationId 请求 409 `stale_activation` | 使用了停用/过期激活的 ID | 重新激活取新 ID（预期行为，docs/07 §3） |
| 端口冲突（8088/5173/5432 被占） | 本机其他服务 | `.env` 覆盖 `BACKEND_PORT`/`FRONTEND_PORT` |
| 前端白屏/接口跨域 | 直连 8080 等错误端口 | 前端固定走 5173 同源代理；后端 8088 仅 API/健康检查 |

## 6. 种子数据与清理

- 库存插件 V002 迁移自带三行幂等种子（`ON CONFLICT sku DO NOTHING`）；其余演示数据由脚本/人工现场产生。
- 彻底重置：`docker compose down -v`（**删除数据卷**，含全部业务数据与账号；仅演示环境使用）。

## 7. 变更记录

| 日期 | 变更 |
| --- | --- |
| 2026-08-29 | P14 迭代 2 新增：答辩运行手册（启动/账号/演示/三保险/排障/清理） |
