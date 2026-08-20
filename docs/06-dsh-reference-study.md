# dsh 插件化设计学习记录

**参考目录**：`docs/dsh/deepseek-harness-master`  
**学习日期**：2026-08-20  
**结论**：吸收运行时插件化思想，不直接复制其 TypeScript/Cordis 实现

## 1. dsh 的核心不是“插件包”

dsh 的架构文档明确采用“**一切皆插件**”：模型适配器、工具注册表、会话日志、Agent Loop、设置页面和浏览器组件都通过插件挂载到共享 Context。插件通过 `inject` 声明服务依赖，通过 `apply(ctx)` 安装行为，通过 `ctx.effect()`、事件监听器和注册表 disposer（资源释放函数）管理可逆副作用。

这带来一个重要启发：插件化不应只表示“把一组文件压缩成包”，还应表示**系统能力通过稳定扩展点接入，且可以被替换、停用和卸载**。

## 2. 值得借鉴的设计

### 2.1 Context + Service

插件不直接依赖具体实现，而是从 Context 获取服务。服务由提供方注册，消费方通过稳定的服务键或接口使用。这样可以替换数据库、模型、通知和存储实现，而不修改业务插件。

对 FlexForge 的映射：建立 `PluginContext`、`ServiceRegistry` 和少量稳定接口，例如 `MetaRegistry`、`DataAccess`、`AuditLog`、`NotificationSender`。MVP 不需要完整的通用依赖注入容器，但必须禁止业务插件直接依赖 Controller 或数据库连接。

### 2.2 能力 seam：定义、提供、消费三件套

dsh 将可替换能力拆成 Service Definition、Service Provider 和 Consumer。单独增加一个工具函数不算扩展点，必须同时定义接口、实现和使用者。

对 FlexForge 的映射：每个插件扩展点都要有明确契约。例如“动作处理器”至少需要动作类型 Schema、执行接口和工作流引擎调用方；“菜单贡献”需要菜单数据契约、注册表和前端渲染方。

### 2.3 配置组合层

dsh 用 profile、bundle 和 patch 分层组合运行时。基础能力先装配，用户 patch 在上层覆盖；配置行以稳定 id 定位，patch 替换完整配置，启动后可以 dump 实际配置树。

对 FlexForge 的映射：保留“平台默认插件 -> 企业/项目配置 -> 用户覆盖”的三层模型。插件自身提供 manifest，系统配置记录启用状态和参数，用户配置只覆盖白名单字段。MVP 先实现数据库中的配置层，不实现完整 YAML Loader。

### 2.4 可逆生命周期

dsh 的注册、事件监听、工具、定时器和子 Fiber 都归属于插件生命周期；插件卸载时统一 dispose，不留下半完成注册。动态 Host 半启动失败时会先清理 Fiber，再把失败返回给调用方。

对 FlexForge 的映射：安装、启用、停用和卸载必须是显式状态迁移。所有注册操作返回 disposer 或绑定 `activationId`，失败安装需要回滚菜单、权限、元数据和迁移记录。

### 2.5 版本与激活尝试分离

dsh 的动态 Cordis Runner 将稳定 Plugin ID、不可变 Package ID 和一次激活的 Run ID 分开，并保留 current/next/latestRun。这样可以识别过期响应、并发更新和失败版本。

对 FlexForge 的映射：插件表应区分 `plugin_id`（稳定身份）、`plugin_version_id`（不可变版本包）和 `plugin_activation_id`（一次安装/启用尝试），并保留 `current_version_id`、`next_version_id`。

### 2.6 Host/Client 双面契约

dsh 的动态插件可以有 Host half 和 Client half。Host 先启动，浏览器半通过带相关 ID 的请求获得精确版本代码；客户端注册失败和渲染失败会单独上报，不能伪装成 Host 启动成功。

对 FlexForge 的映射：将业务插件拆成 Server Contributions 和 Client Contributions。MVP 的 Client Contributions 只允许引用平台内置 renderer，例如 `table`、`form`、`status-badge`，不执行上传的 JavaScript。未来若开放代码插件，再单独设计签名、隔离和版本协议。

### 2.7 前端 Slot

dsh 的 UI slot 是有名字、有类型、有作用域的扩展点，支持 single、list、keyed 和 chain 四种形态。注册时可以声明子 slot，disposer 会递归清理子贡献；以命名空间为键可以让外部插件接入而无需主页面了解其业务语义。

对 FlexForge 的映射：前端建立少量稳定 slot。自 2026-08-20 起 ID 以 [扩展点登记册](./extension-points.md) 为准：`extension.navigation`、`extension.field-renderer`、`extension.record-action`；早期草案中的 `entity.list.column`、`entity.form.field`、`issue.panel` 未登记，不进入 MVP，未来需要时再按登记流程启用。MVP 只实现 `extension.navigation` 与 `extension.field-renderer` 的 keyed 注册。

### 2.8 运行时可观测性

dsh 提供 inventory、snapshot、inspect 和结构化诊断，区分“没有注册”“等待依赖”“启动失败”“渲染失败”和“过期调用”。这让插件问题可解释，而不是只显示一个通用 500。

对 FlexForge 的映射：插件管理页需要显示版本、激活尝试、依赖、状态、失败阶段、错误摘要和审计记录；API 调用携带 `pluginActivationId`，过期激活请求必须被拒绝。

## 3. 不直接复制的内容

| dsh 机制 | FlexForge 的处理 |
| --- | --- |
| Cordis Context/Fiber | 借鉴生命周期和服务注册思想；使用 Java 接口、Spring 生命周期和 `PluginContext` 实现，不引入 Cordis。 |
| 动态 `node:vm` 代码执行 | MVP 禁止。声明式插件已足以证明元数据和插件复用；代码沙箱属于未来研究。 |
| Host/Client 动态源码下发 | MVP 只传输 JSON manifest 和 renderer 名称；不下发任意前端源码。 |
| 完整 profile/bundle/patch Loader | MVP 用数据库配置和版本化 manifest 替代，保留分层概念。 |
| 全量事件溯源会话日志 | FlexForge 只对 Issue、插件激活和审计做追加事件记录，不把所有业务数据改造成事件溯源。 |
| 高度泛化的 slot 类型系统 | 先实现少量业务扩展点，等有两个以上真实插件后再抽象公共 slot。 |

## 4. 对 FlexForge 架构的直接影响

1. “插件管理模块”升级为“插件运行时”：除了安装包，还负责依赖、注册、激活、停用、回滚和诊断。
2. 元数据引擎不直接被 Controller 调用，而通过 `MetaRegistry` 和 `DataAccess` seam 提供能力。
3. Issue/Agent 生成的产物改为版本化声明式插件包，不生成并直接执行任意后端代码。
4. 前后端 API 需要携带插件版本和激活尝试身份，拒绝 stale（过期）调用。
5. 文档和测试必须覆盖 disposer、失败回滚、重复安装、并发启停和依赖缺失。

