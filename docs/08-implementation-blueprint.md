# FlexForge 实施蓝图

**版本**：v0.1  
**日期**：2026-08-20  
**适用范围**：MVP 实现前的模块、接口、数据流和生命周期约束

## 1. 实现目标

将当前架构从概念层落到可编码的模块化单体。实现顺序遵循：

```text
工程基线
  -> 核心契约
  -> 认证与权限
  -> 元数据写模型
  -> 动态数据运行时
  -> 前端动态渲染
  -> 插件包与运行时
  -> Issue / AI
  -> 端到端验收
```

任何阶段都不得绕过前一阶段的契约直接堆叠页面或业务逻辑。

系统骨架（P01-P08，以及 P10-P12 的平台部分）不包含任何业务模块；P09 库存是演示交付物，必须以可停用/卸载的普通 Level 1 插件存在（ADR-0004）。

## 2. 后端模块边界

```text
backend/
  flexforge-app/          启动、配置、REST 装配、健康检查
  flexforge-common/       ID、错误模型、分页、JSON、时间、日志
  flexforge-runtime/      PluginRuntime、Context、Service/Extension Registry
  flexforge-auth/         登录、JWT、RBAC、当前用户
  flexforge-system/       用户、角色、菜单、审计
  flexforge-meta/         Entity/Field/View 定义和 MetaRegistry
  flexforge-data/         动态数据访问、查询白名单、记录校验
  flexforge-plugin/       包校验、版本、依赖、激活、回滚
  flexforge-issue/        Issue、评论、标签、状态机
  flexforge-ai/           模型适配、规格 Schema、插件生成器
  flexforge-workflow/     可选，M7 后再实现
```

上述模块是平台骨架；业务能力（含 P09 库存示例）禁止编译进这些模块，必须以 Level 1 插件交付（ADR-0004）。

每个模块内部遵循：

```text
api -> application -> domain -> infrastructure
```

- `api`：Controller、请求/响应 DTO、鉴权声明。
- `application`：用例编排、事务边界、幂等和权限检查。
- `domain`：实体、值对象、状态机、领域服务和端口接口。
- `infrastructure`：Repository、数据库、外部 LLM、文件存储和适配器。

禁止规则：Controller 不能直接调用 Repository；插件不能依赖 Controller；跨模块只能依赖公开的 application service、domain port 或事件。

治理约束：文件长度/复杂度硬上限见 `docs/coding-standards.md` §7；扩展点唯一登记册见 `docs/extension-points.md`；依赖方向与 seam 预留规则见 `docs/10-engineering-governance.md`。

## 3. PluginRuntime 最小实现

### 3.1 核心对象

```text
PluginDescriptor       插件静态描述和能力等级
PluginPackage          不可变版本包和 contentHash
PluginInstance         稳定插件身份
ActivationContext      一次激活的注册和清理边界
PluginContext          插件访问服务和扩展点的门面
ServiceRegistry        平台服务提供/查找
ExtensionRegistry      菜单、renderer、动作等贡献
```

### 3.2 最小接口

```java
public interface PluginRuntime {
    ValidationReport validate(PluginArchive archive);
    InstallPreview preview(PluginPackageId packageId);
    ActivationResult activate(PluginInstanceId pluginId, PluginVersionId versionId);
    void stop(ActivationId activationId);
    void uninstall(PluginInstanceId pluginId);
}

public interface PluginContext {
    <T> T require(ServiceKey<T> key);
    <T> Optional<T> get(ServiceKey<T> key);
    <T> Registration register(ExtensionPoint<T> point, T contribution);
    Registration on(DomainEventType type, DomainEventHandler handler);
}
```

`Registration` 必须幂等，重复 `close()` 不报错。激活上下文关闭时按逆序释放所有注册项。

### 3.3 激活流程

```text
上传
  -> 解包和路径检查
  -> manifest Schema 校验
  -> contentHash 计算
  -> 平台版本/能力等级检查
  -> 依赖解析
  -> 生成 InstallPreview
  -> 创建 activationId
  -> 执行插件迁移（PluginRuntime 内建 runner，非 Flyway，见 ADR-0005）
  -> 注册 metadata/menu/permission/renderer
  -> 状态 ACTIVE
```

任意步骤失败：写入失败阶段和错误码，关闭已创建的 `ActivationContext`，回滚事务和注册记录，保留失败审计事件。

插件迁移 runner 规则（ADR-0005）：

- 平台骨架迁移走 Flyway（`classpath:db/migration`）；插件 `migrations/` 不进 Flyway。
- runner 按 `resources.migrations` 声明顺序执行 `V*__*.sql`，每脚本计算 checksum 并写 `plugin_migration` 记录，与安装同一事务。
- 脚本只能操作以插件短名前缀命名的对象或插件数据；禁止修改 `sys_*`/`meta_*`/`plugin_*` 平台表结构，越界脚本在 runner 校验层拒绝。
- 同一版本重复安装跳过已应用且 checksum 一致的脚本；checksum 变化拒绝安装。

## 4. Level 1 插件格式

```text
example-inventory/
  plugin.json
  metadata/entities/inventory-item.json
  metadata/views/inventory-item.list.json
  metadata/views/inventory-item.form.json
  migrations/V001__inventory_item.sql
  assets/
```

`plugin.json` 至少包含：

```json
{
  "schemaVersion": 1,
  "id": "example.inventory",
  "name": "库存示例",
  "version": "0.1.0",
  "capabilityLevel": 1,
  "minPlatformVersion": "0.1.0",
  "dependencies": [],
  "permissions": ["inventory.read", "inventory.write"],
  "contributions": {
    "navigation": ["inventory.items"],
    "renderers": ["integer.default"],
    "themeAssets": [
      {"key": "example.inventory.bg", "kind": "background", "path": "assets/bg.webp"}
    ]
  },
  "resources": {
    "entities": ["metadata/entities/inventory-item.json"],
    "views": ["metadata/views/inventory-item.list.json", "metadata/views/inventory-item.form.json"],
    "migrations": ["migrations/V001__inventory_item.sql"]
  }
}
```

资源路径必须是包内相对路径，禁止 `..`、绝对路径、脚本文件和未声明文件。renderer 只能引用平台注册的 ID（六类内置 `<type>.default`，见登记册 §2.2）。
`contributions.themeAssets` 为对象数组（登记册 §2.2 契约：`key/kind/path` 必填，`scope` 可选）；P08 起 `assets/` 区域**逐文件声明**——包内每个资产文件必须被某条 themeAssets 贡献引用，themeAssets 引用的资产必须存在于包内（双向核对，Issue #20 第 3 项）。资产经导入存储（`plugin_version.asset_payloads` base64），由激活身份限定的 serve 端点取回（响应头 `Content-Security-Policy: default-src 'none'` + `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff`，svg 事件属性纵深，Issue #20 第 4 项）。

`plugins/example-inventory` 与任何第三方包同权：预置但不自动安装；演示脚本只能调用标准导入/安装/启停/卸载 API；禁止在前后端骨架中为其写死菜单、路由、页面或权限。

插件资源 JSON 契约（P09 起，激活期消费）：实体 `{"name","displayName","fields":[{name,displayName,fieldType,required?,validation?,position}]}`；
视图 `{"entity","viewType":"list|form|kanban","name","groupBy"?,"columns":[{field}],"filters"?}`（entity 必须为同包注册实体；kanban 视图 groupBy 必填且为同实体 enum 字段——列=枚举选项，P17，登记册 §2.3）。
迁移 runner 按 `resources.migrations` **声明顺序**执行（多脚本顺序由 manifest 决定）。
包目录只允许 manifest/metadata/migrations/assets 区域——安装说明等文档放包外（如 `plugins/<name>-README.md`）。

`migrations/` 只允许 `V<序号>__<名称>.sql` 顺序脚本，由 PluginRuntime runner 执行（规则见 §3.3，ADR-0005）；不允许放入可执行脚本或 Flyway 专用配置。

## 5. 元数据和动态数据实现取舍

MVP 使用“受控动态实体表 + JSONB 扩展数据”方案：

- 实体、字段、视图定义存放在 `meta_*` 表。
- 业务记录使用受控主表保存 `entity_id`、`record_id`、审计字段和 `data_json`。
- 查询字段先通过 `MetaRegistry` 映射到白名单，再生成参数化 SQL。
- 复杂索引暂不自动创建；需要索引的字段由迁移资源显式声明。

这样可以先验证动态 CRUD 和插件复用，避免第一版为任意用户输入生成数据库表结构。

## 6. 前端动态扩展

前端建立轻量 keyed registry，不直接加载插件源码：

```text
extension.navigation   菜单项和目标实体
extension.field-renderer    fieldType -> 内置 renderer ID
extension.record-action     actionType -> 内置 action handler
extension.layout            页面/工作台槽位与部件编排（proposed，P06/P07 落地）
extension.theme-asset       背景图/图标/动画等美术资产更换（proposed，P06/P07 落地）
```

以上 ID 以 `docs/extension-points.md` 为准。`plugin.json` 的 `contributions.navigation` 映射到 `extension.navigation`，`contributions.renderers` 映射到 `extension.field-renderer`。页面根据服务端返回的实体/视图元数据，使用 renderer registry 选择内置组件。插件停用后，注册表按 `activationId` 清理；页面收到 stale 状态时重新拉取插件清单和元数据。GUI 定制口径（2026-08-24 澄清）：全部经声明式扩展点（部件/布局/美术资产，FR-PLUGIN-09/10/11），不做可视化拖拽设计器。

## 7. API 契约

插件相关 API 必须带上版本和激活信息：

```json
{
  "pluginId": "example.inventory",
  "pluginVersionId": "pkg-001",
  "activationId": "act-004",
  "requestId": "req-123"
}
```

统一错误码至少包括：`invalid_manifest`、`unsupported_schema_version`（P07 schemaVersion 分派层）、`dependency_missing`、`migration_failed`、`registration_failed`、`activation_not_found`、`stale_activation`、`permission_denied`、`validation_error`、`not_found`、`internal_error`、`unauthorized`（后四类为 P02 统一错误装配与 P03 认证引入，代码见 `flexforge-common` `ErrorCodes`）、`invalid_transition`（P10 Issue 状态机：非法迁移/缺原因/缺合法规格批准门）、`model_unavailable`（P11 模型超时/限流/离线，503）、`model_output_invalid`（P11 模型输出重试超限，400 可走手工规格）、`processor_input_invalid`（P23 文件处理器上传未过扩展名/魔数/大小三重校验，400）、`artifact_not_found`（P23 处理器产物不存在或无权访问，404 防枚举同码）、`artifact_expired`（P23 产物过 TTL，410）。`

RequirementSpec 规格契约（P10，唯一事实源 `flexforge-ai` `RequirementSchema`，当前 v1）：`schemaVersion=1` + `summary` + `entities[]`（snake 名/displayName/`fields[]`：fieldType 六类白名单 + validation 复用 FieldTypeRegistry 规则键）+ 可选 `views[]`（复用 ViewRules：实体/列白名单）+ 可选 `permissions[]`/`rules[]`（name+description）+ 必填 `acceptance[]`（验收标准非空）。

## 8. Definition of Done

任何阶段只有同时满足以下条件才算完成：

- 代码、数据库迁移、测试、文档和状态文件已同步。
- 正常路径、权限拒绝和主要失败路径均有验证。
- 在干净环境或固定 fixture 中可重复运行。
- 没有遗留 TODO 被当作已完成能力。
- `STATUS.md` 的当前阶段、验收结果和更新时间已更新。

