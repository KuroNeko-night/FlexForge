# 插件运行时数据模型与验收

## 1. 持久化对象

| 表 | 作用 | 关键字段 |
| --- | --- | --- |
| `plugin_instance` | 稳定的插件实例 | `plugin_id`、`name`、`status`（随生命周期派生：`imported/active/stopped/failed/uninstalled`，Issue #22 评论-16） |
| `plugin_version` | 不可变的插件包版本 | `plugin_version_id`、`plugin_id`、`version`、`content_hash`、`manifest_json`、`capability_level`、`script_payloads`、`resource_payloads`、`asset_payloads` |
| `plugin_dependency` | 版本依赖关系 | `plugin_version_id`、`dependency_id`、`version_range` |
| `plugin_activation` | 一次安装/启停/升级尝试 | `activation_id`、`plugin_version_id`、`operation`、`status`、`stage`、`error_code`、`requested_by` |
| `plugin_registration` | 激活期间产生的注册记录 | `activation_id`、`extension_type`、`registration_key`、`payload_json` |
| `plugin_migration` | 插件版本内已应用的迁移脚本（PluginRuntime runner 记录，非 Flyway） | `plugin_version_id`、`activation_id`、`script_name`、`checksum`、`applied_at` |
| `plugin_audit_event` | 追加式插件领域事件 | `event_id`、`plugin_id`、`activation_id`、`event_type`、`payload_json`、`occurred_at` |

> MVP 实现口径（对齐 V006/V007）：`plugin_instance` 不设 `current_version_id/next_version_id/owner_id` 列；"当前版本"语义由 `plugin_activation` 的同插件唯一占用（V009 部分唯一索引 + 应用层检查）承载，升级失败走补偿性重新激活旧版本而非 next 列状态位。

## 2. 内存对象

数据库记录描述事实，运行时内存对象持有行为：

```text
PluginRuntime
  -> PluginInstanceHandle
       -> ActivationContext
            -> Disposable registrations
            -> Service/Extension contributions
```

`Disposable` 不作为函数持久化。重启恢复时从 `plugin_version` 落库载荷（`manifest_json` + `resource_payloads`）重建 ACTIVE 插件的注册并持有撤销句柄（`plugin_registration` 是激活期注册记录与排障线索，非恢复事实源——V007 建表注释中"恢复依据 plugin_registration"为历史口径；迁移文件不可改，以本节为准）；停用或回滚时先撤销内存句柄，再更新持久化状态。这样数据库不会保存不可执行的运行时对象。

## 3. 状态与幂等

- 同一 `plugin_version_id` 的校验和内容哈希必须稳定；内容变化必须生成新版本。
- 同一插件同一时刻只能有一个 `STARTING` 或 `ACTIVE` 激活。
- 重复提交相同操作应返回已有 `activation_id`，不得重复注册。
- 迁移幂等：同一 `plugin_version_id` 的已应用脚本（checksum 一致）直接跳过；checksum 变化视为包损坏，拒绝安装（ADR-0005）。
- `STOPPING`、`UNINSTALLING` 期间的新操作必须排队或明确拒绝。
- 只有 `ACTIVE` 激活可以服务业务 API；旧激活即使仍有网络请求也必须返回 `stale_activation`。

## 4. 失败阶段

激活阶段至少区分：

```text
VALIDATE -> DEPENDENCY_CHECK -> MIGRATION -> REGISTER -> READY
                         \-> ROLLBACK
```

失败记录原始阶段、稳定错误码、用户可读摘要和关联 request ID。回滚失败需要升级为 P0/P1 缺陷，不得简单标记为“安装失败”后继续运行。

## 5. 必测用例

1. 合法插件安装后注册菜单、权限、实体和 renderer。
2. 缺失依赖在 `DEPENDENCY_CHECK` 失败，数据库和注册表无残留。
3. 迁移中途失败时，已注册贡献和事务数据均回滚。
4. 同一版本重复安装返回同一结果，不产生重复菜单或权限。
5. 停用后旧 `activation_id` 的 API、异步任务和前端请求均被拒绝。
6. 升级失败时补偿性重新激活旧版本，"当前版本"保持可用；新版本激活记录进入失败状态（无 next 列，语义由占用行+补偿承载，见 §1 口径注）。
7. 卸载后注册表、菜单、权限和监听器全部撤销，审计记录仍保留。
8. 重启后只恢复持久化的 ACTIVE 插件，并重新建立内存 disposer。

