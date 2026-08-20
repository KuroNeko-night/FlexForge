# ADR-0002：FlexForge 采用运行时插件模型，但 MVP 只开放声明式插件

**状态**：Accepted  
**日期**：2026-08-20  
**参考**：[dsh 插件化学习记录](../06-dsh-reference-study.md)

## 背景

原设计把插件主要定义为一个包含 manifest、前后端代码和迁移脚本的压缩包。这可以解决分发问题，但不能充分表达插件对平台服务、扩展点、生命周期和失败清理的依赖。dsh 的实践说明，真正可维护的插件系统需要运行时插件树、稳定服务接口、可逆注册和版本化激活。

同时，毕业设计不适合直接执行不受信任的 Java 或 JavaScript 代码，也不适合实现完整沙箱。

## 决策

FlexForge 引入轻量 `PluginRuntime`，提供以下概念：

```text
PluginDescriptor  ->  PluginContext  ->  Service/Extension Registry
        |                    |                    |
  version identity      dependency access      reversible contribution
```

插件分为三个能力等级：

- **Level 0：内置编译插件**。由后端构建产物提供，可实现复杂服务。
- **Level 1：声明式业务插件（MVP）**。只包含 manifest、实体/字段/视图/菜单/权限/动作配置和迁移资源；不执行任意代码。
- **Level 2：受信代码插件（未来）**。需要签名、隔离运行时、资源限制和兼容性协议，毕业设计不承诺实现。

插件运行时维护稳定 `pluginId`、不可变 `pluginVersionId` 和一次激活的 `activationId`。安装、启用、停用、升级、回滚和卸载都是状态迁移；每个注册项都绑定激活身份并可撤销。

## 影响

### 正面影响

- 插件不再只是文件格式，而是可观察、可回滚的运行时对象。
- AI 生成结果可以严格限制为 JSON Schema 和白名单资源。
- 未来可在不改变业务插件契约的情况下增加编译插件或安全代码扩展。

### 负面影响

- 需要额外的 `PluginRuntime`、注册表和激活记录。
- 业务插件的自由度受限，复杂业务必须等待平台扩展点。
- 前端扩展点需要设计和测试，不能任意导入第三方组件。

## MVP 约束

- 禁止上传并执行任意后端源码或前端脚本。
- 插件前端只能引用平台内置 renderer ID。
- 安装失败必须回滚注册信息；数据迁移默认不可逆删除需要二次确认。
- 任何 API 操作都校验 `pluginVersionId` 和当前 `activationId`。

