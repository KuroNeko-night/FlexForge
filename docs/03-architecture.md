# FlexForge 总体架构

## 1. 架构决策

MVP 采用**前后端分离的模块化单体 + 轻量运行时插件模型**，不拆微服务。后端按领域模块组织，但共用一个应用进程和数据库。插件边界通过 descriptor、Service/Extension Registry、接口和数据库迁移目录隔离，后续若有真实性能或团队规模需求，再评估服务拆分。

项目借鉴 dsh 的“运行时插件树”思想，但不引入 Cordis，也不在 MVP 中执行任意上传代码。业务插件分为内置编译插件和声明式插件；AI 只生成后者。

## 2. 逻辑分层

```text
Browser
  -> Vue 3 + TypeScript
  -> REST API
  -> Spring Boot modular monolith
       PluginRuntime
         -> ServiceRegistry / ExtensionRegistry / EventBus
       auth / system / meta / plugin / issue / ai / workflow(optional)
  -> PostgreSQL
  -> external LLM (optional, OpenAI-compatible)
```

每个后端模块遵循 `api -> application -> domain -> infrastructure` 的依赖方向。Controller 不直接操作数据库；跨模块调用优先使用应用服务接口、Service seam 或领域事件。

## 3. 运行时插件模型

### 3.1 核心契约

```java
interface FlexPlugin {
    PluginDescriptor descriptor();
    void apply(PluginContext context);
}

interface PluginContext {
    <T> T require(ServiceKey<T> key);
    <T> Optional<T> get(ServiceKey<T> key);
    <T> Disposable register(ExtensionPoint<T> point, T contribution);
    Disposable on(DomainEventType type, EventHandler handler);
    void effect(AutoCloseable cleanup);
}
```

这是概念契约，不要求第一阶段直接创建通用框架。它表达三条硬规则：依赖通过服务获取、能力通过扩展点注册、副作用必须可撤销。

### 3.2 插件能力等级

| 等级 | 内容 | MVP |
| --- | --- | --- |
| Level 0 | 内置编译插件，仅限平台内核（auth/system/meta/data/plugin/issue/ai 的复杂服务） | 必做基础设施 |
| Level 1 | manifest + 元数据 + renderer ID + 受控迁移 | 必做业务插件 |
| Level 2 | 签名的受信代码插件，需要隔离运行时 | 未来扩展 |

业务能力（含演示库存）一律从 Level 1 起步；Level 0 不得承载业务，预置示例与第三方插件同权（ADR-0004）。

### 3.3 插件身份和状态

系统分开存储 `pluginId`、`pluginVersionId` 和 `activationId`。推荐状态：

```text
DEFINED -> VALIDATED -> INSTALLED -> STARTING -> ACTIVE
              |             |           |
            FAILED       UNINSTALLED  STOPPING -> STOPPED
```

升级采用 current/next 模型：先校验并准备 next 版本，激活成功后再替换 current；失败时保留 current。所有菜单、权限、元数据、事件监听和 renderer 注册都绑定到 activationId。

### 3.4 MVP 扩展点

扩展点的唯一登记册是 [docs/extension-points.md](./extension-points.md)，命名与演化规则以登记册为准。v1 摘要：

- `service.meta`：实体和字段元数据。
- `service.data-access`：受控动态数据读写。
- `service.audit`：审计事件写入端口。
- `extension.navigation`：菜单和路由元数据。
- `extension.field-renderer`：字段类型到内置 renderer 的映射。
- `extension.record-action`：白名单动作类型。
- `event.domain`：Issue、插件激活和数据变更事件。

每个扩展点必须有定义、注册服务和消费方；没有消费方的注册表不进入 MVP。`active` 扩展点必须有注册-撤销测试，新增/变更流程见登记册。

### 3.5 系统骨架与业务边界

- 平台骨架 = `flexforge-app/common/runtime/auth/system/meta/data/plugin/issue/ai` + 前端 core/通用组件/系统页面。骨架只提供平台能力，不包含任何业务实体、菜单、页面或业务权限种子。
- 所有业务能力（含演示库存）必须作为 Level 1+ 插件交付并通过 PluginRuntime 注册；Level 0 仅限平台内核（ADR-0004）。
- `plugins/example-inventory` 是预置交付物，不是骨架的一部分：不写入编译产物、不自动安装；安装/启停/卸载与第三方插件走完全相同的 API 与状态机。
- 根 `database/migrations/` 只放平台迁移；业务迁移在插件包内由插件安装流程执行。

## 4. 元数据模型

MVP 的元数据至少包括：

- `meta_entity`：实体标识、显示名称、状态、所属插件。
- `meta_field`：字段标识、数据类型、必填、默认值和校验规则。
- `meta_view`：列表列、筛选条件、表单顺序和可见性。

字段类型使用白名单枚举。所有动态查询必须通过字段映射和参数绑定生成，禁止直接拼接用户传入的表名、列名或 SQL 片段。**存储定案（2026-08-24，P05）**：第一版采用单 JSONB 记录表 `data_record`（entity_id 外键 + data JSONB；GIN 索引为未来 containment 查询预留，P05 过滤/排序走 `data->>'f'` 类型转换表达式、演示量级顺序扫描满足 P95 基线）+ 元数据驱动映射——按实体建表（动态 DDL）方案不采用，理由：配置 API 建实体零 DDL、P04 breaking 规则保护字段语义、迁移复杂度最低；此结论变更属架构级，需新 ADR。

## 5. 插件包模型

插件是声明式资源包，而不是任意代码执行包：

```text
example-inventory/
  plugin.json
  metadata/entities/*.json
  metadata/views/*.json
  migrations/*.sql
  assets/
```

`plugin.json` 记录 `id`、`name`、`version`、`capabilityLevel`、`minPlatformVersion`、`dependencies`、`permissions`、`menus`、renderer ID 和资源路径。安装器按以下顺序工作：解析 -> Schema 校验 -> 依赖检查 -> 白名单检查 -> 预览变更 -> 创建 activationId -> 事务安装 -> 注册贡献 -> 写入版本和审计记录。

`plugin.json.schemaVersion` 的演化规则（additive/breaking）见 [docs/10-engineering-governance.md](./10-engineering-governance.md) §4；扩展点与 `contributions` 键的映射见 [docs/extension-points.md](./extension-points.md) §2.4。

卸载默认为“停用并清理注册信息”，数据表和业务数据是否删除需要显式确认，避免误删。

## 6. AI Agent 边界

AI 服务只负责：

1. 根据对话生成结构化需求草稿。
2. 根据已确认规格生成声明式插件骨架。
3. 解释校验错误并建议修复。

AI 不直接获得数据库管理员权限、服务器命令权限或生产发布权限。所有输出经过 JSON Schema、资源白名单、插件能力等级和人工确认；模型不可用时使用手工规格和固定 fixture。生成器输出的是 Level 1 声明式插件包，不是可执行后端项目。

## 7. Issue 状态机

```text
待审核 -> 已批准 -> 待测试 -> 测试通过 -> 已完成
   |         |          |           |
退回修改   开发失败   反馈修复     关闭
```

状态迁移必须由领域服务统一执行，记录操作者、原因和时间。前端不允许自行修改状态字段绕过规则。

## 8. 关键接口（草案）

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/v1/auth/login` | 登录并返回令牌 |
| `GET` | `/api/v1/meta/entities` | 查询实体元数据 |
| `GET` | `/api/v1/meta/entities/by-name/{name}` | 按名称取实体定义（动态页面元数据入口，P06） |
| `POST` | `/api/v1/meta/entities` | 创建实体 |
| `GET` | `/api/v1/data/{entity}` | 查询动态实体数据（分页/白名单排序/白名单过滤） |
| `POST` | `/api/v1/data/{entity}` | 新增动态记录 |
| `GET` | `/api/v1/data/{entity}/{id}` | 记录详情 |
| `PATCH` | `/api/v1/data/{entity}/{id}` | 编辑记录（显式 null 清除字段值；并发冲突返回可诊断 400，updated_at 乐观守卫） |
| `DELETE` | `/api/v1/data/{entity}/{id}` | 物理删除记录（审计事件） |
| `POST` | `/api/v1/plugins/validate` | 校验插件包 |
| `POST` | `/api/v1/plugins/import` | 导入插件包（幂等，P07 落地） |
| `GET` | `/api/v1/plugins/activations/{activationId}/registrations` | 激活注册清单（旧 activationId 返回 `stale_activation`，P08 落地） |
| `GET` | `/api/v1/plugins/activations/{activationId}/assets/{path}` | 插件静态资产（theme-asset 消费面，登录可读；path 为完整存储键含 `assets/` 前缀；CSP/attachment/nosniff 响应头纵深，P08 落地） |
| `POST` | `/api/v1/plugins/install` | 安装插件（P07 起由 import 承担） |
| `POST` | `/api/v1/plugins/{id}/activate` | 启用指定插件版本 |
| `POST` | `/api/v1/plugins/{id}/stop` | 停用当前激活 |
| `GET` | `/api/v1/plugins/inventory` | 查看插件版本、激活和失败诊断 |
| `POST` | `/api/v1/issues` | 创建 Issue |
| `POST` | `/api/v1/issues/{id}/clarify` | AI 澄清需求 |
| `POST` | `/api/v1/issues/{id}/generate` | 生成插件骨架 |
| `POST` | `/api/v1/issues/{id}/transition` | 执行合法状态迁移 |

## 9. 可观测性与失败处理

- 统一请求 ID，日志中不记录密码、令牌和完整提示词中的敏感数据。
- Agent 任务记录输入规格版本、模型、重试次数、输出校验结果和错误信息。
- 插件安装和数据库迁移采用事务；外部调用失败时状态可重试且不重复执行已完成动作。
- 插件 API、远程调用和异步任务校验 activationId；过期调用返回 `stale_activation`，不触碰当前版本。
- 提供健康检查、数据库迁移状态和模型连接测试接口。

