# 仓库维护约束

## 1. 目录约定

```text
backend/                 后端源码
frontend/                前端源码
plugins/                 示例和可安装插件
database/migrations/     数据库迁移
database/seed/           演示数据
docs/                    项目文档、ADR、测试记录
scripts/                 可重复执行的开发脚本
tests/                   跨模块和端到端测试
```

目录可随实现调整，但变更必须同步 README 和相关文档。不要把临时导出物、模型原始响应、个人笔记和 IDE 配置提交到仓库。

## 2. 分支与提交

- `main` 始终保持可构建、可演示。
- 功能分支使用 `feat/<short-name>`，修复分支使用 `fix/<short-name>`，文档分支使用 `docs/<short-name>`。
- 提交信息采用 `<type>(<scope>): <summary>`，例如 `feat(meta): add enum field validation`。
- 一个提交只解决一个主题；格式化、重命名和业务改动不要混在同一提交。
- 合并前必须通过构建、测试、格式检查和文档检查。

## 3. 质量门禁（CI 阻断项）

以下检查在 CI 中执行，任一失败即阻断合并：

1. 格式检查（Prettier / Spotless 或等价工具）。
2. 数值硬门禁：文件长度、函数长度、圈复杂度、参数个数、嵌套深度按 `docs/coding-standards.md` §7 执行；硬上限 = error，目标值 = warn。
3. 依赖边界：ArchUnit/import 规则覆盖无循环依赖、无跨模块 `infrastructure` 引用、未标注 `@PublicApi`/`@ExperimentalApi` 的类不得被跨模块引用。
4. 回归冒烟与治理自检：`scripts/check-repo-health` 至少覆盖 `docs/11-regression-test-plan.md` §3 的 R-GOV-01..06；违规样例（fail-open guard）必须按预期失败，若意外通过视为 P0。
5. 单元、集成与启动冒烟测试。
6. 文档检查：Markdown 相对链接有效、`docs/project-status.json` 可解析且与 `STATUS.md` 锚点一致。

新增扩展点、服务 seam 或公开契约前，必须先完成 `docs/extension-points.md` 登记，否则评审不通过。

## 4. 受保护内容

以下内容不得直接提交：

- `.env`、API 密钥、JWT 密钥、数据库真实密码。
- 生产数据库导出、用户隐私和未脱敏日志。
- 由 IDE 或构建工具生成的缓存、依赖目录和临时文件。
- 未经过人工审核的 AI 生成代码或插件包。

## 5. 数据库和插件约束

- 数据库结构只能通过编号迁移变更；根 `database/migrations/` 只允许平台骨架迁移（`sys_*`/`meta_*`/`plugin_*` 等），业务表迁移必须位于插件包内并由插件安装流程执行（ADR-0004）。
- 迁移必须幂等或明确记录不可逆操作；破坏性变更需要备份和回滚说明。
- 插件必须经过 manifest Schema、依赖、路径和资源大小校验。
- 插件必须声明能力等级；Level 1 只能引用白名单扩展点和 renderer ID，禁止提交任意可执行源码。
- 插件版本包不可变；安装实例、版本和激活尝试分开记录。
- 插件安装、启停和卸载必须可审计；默认停用而不是删除业务数据。
- 每个注册项必须绑定 activationId 并可通过 disposer 撤销，避免停用后残留菜单、权限、监听器或定时任务。

## 6. 依赖与版本

- 锁定 JDK、Node、包管理器、数据库和 Docker 基础镜像的大版本。
- 新增依赖需要说明用途、许可证、维护活跃度和替代方案。
- 每次升级基础依赖先在独立分支运行完整测试和启动检查。

## 7. 发布与备份

- 发布前生成版本号、变更摘要、数据库迁移清单和回滚步骤。
- 演示环境使用种子数据，不使用真实用户数据。
- 每个里程碑保存可复现的部署包或镜像标签。
