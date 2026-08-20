# ADR-0005：平台迁移用 Flyway，插件迁移由 PluginRuntime 自管

**状态**：Accepted
**日期**：2026-08-20
**参考**：[ADR-0002](./0002-runtime-plugin-model.md)

## 背景

P01 引入数据库迁移工具，P07/P08 要求插件包携带 `migrations/` 并在安装时执行。若把插件包目录也挂进 Flyway 的 locations，会遇到四类问题：

1. Flyway 用全局 `flyway_schema_history` 记录已执行脚本，无法按插件实例/激活隔离，卸载与多版本共存会互相污染。
2. Flyway 的 locations 需要静态配置，动态安装/卸载插件时增删 location 依赖重建 Flyway 实例，脆弱且易出错。
3. 插件迁移失败回滚必须与 `ActivationContext`、`plugin_activation` 的事务边界一致，Flyway 自身的校验与重试语义和 PluginRuntime 状态机不一致。
4. 插件脚本若可操作平台表，Level 1 声明式插件的安全边界（白名单、可回滚）形同虚设。

## 决策

1. **平台骨架迁移**：仅根 `database/migrations/` 的平台脚本（`sys_*`/`meta_*`/`plugin_*` 等）走 Flyway，locations 固定为 `classpath:db/migration`。
2. **插件迁移**：由 PluginRuntime 内建 migration runner 执行，不进 Flyway：
   - 按 `plugin.json` 的 `resources.migrations` 声明顺序执行 `V*__*.sql`；
   - 执行前计算脚本 checksum，并逐脚本写入 `plugin_migration` 表（`plugin_version_id`、`script_name`、`checksum`、`activation_id`、`applied_at`）；
   - 与插件安装同一事务；任一脚本失败即整体回滚并记录 `MIGRATION` 失败阶段；
   - 同一 `plugin_version_id` 重复安装时跳过已应用且 checksum 一致的脚本。
3. **脚本边界**：插件迁移只能创建/操作以插件短名前缀命名的对象或插件数据，禁止修改 `sys_*`/`meta_*`/`plugin_*` 平台表结构；越界脚本在 runner 校验层拒绝。
4. **卸载语义**：`plugin_migration` 记录与审计事件保留；业务表的删除遵循显式 `dataPolicy`（默认保留），不随卸载自动删除。

## 影响

### 正面影响

- 插件生命周期（安装/回滚/升级/卸载）与迁移执行边界完全一致，失败可定位到具体脚本。
- Flyway 保持单一用途，平台迁移可重复执行、可校验。
- 为 Level 1 插件的"受控迁移"划出明确的安全边界。

### 负面影响

- 丢失 Flyway 对插件脚本的生态能力（repeatable migration、命令行修复工具等）；MVP 只需要顺序 DDL/DML，代价可接受。
- 需要自行实现并测试 checksum、跳过已应用与回滚语义（用例已列入 `docs/07` §5 与 RB-PLUGIN-LIFE）。

## 备选方案

1. **全部走 Flyway**：不采用。动态 location 与插件生命周期隔离问题无法低成本解决。
2. **插件不允许携带 SQL**：不采用。迁移是 Level 1 插件的核心能力之一，去掉会削弱演示价值。
3. **人工执行插件脚本**：不采用。无法满足幂等、回滚与审计要求。

## 复审条件

- 插件迁移需求超过顺序 DDL/DML（如 repeatable、条件迁移、并行迁移）时，评估引入专用迁移库或扩展 runner。
- Flyway 提供原生动态 location/命名空间隔离能力后，重新对比两种方案。

## 基线同步

同一变更已同步：`docs/07-plugin-runtime-data-model.md`（新增 `plugin_migration` 表）、`docs/08-implementation-blueprint.md`（§3.3/§4 迁移 runner 规则）、`docs/09-detailed-implementation-plan.md`（P07/P08 迁移 runner 任务与验收）、`docs/11-regression-test-plan.md`（RB-PLUGIN-LIFE 迁移用例）、`AGENTS.md`（ADR 清单）、`STATUS.md`（进度日志）。
