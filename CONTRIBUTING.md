# 贡献指南

## 开始前

1. 阅读 [README.md](./README.md)、[仓库维护约束](./docs/repository-maintenance.md) 和 [编码与注释规范](./docs/coding-standards.md)。
2. 从 Issue 或任务描述确认需求编号、验收标准和影响范围。
3. 在本地启动依赖并运行现有测试，确认基线可用。
4. 按 `docs/repository-maintenance.md` §2.2/§2.3 判断本次改动是"直推 main"还是"建分支 + PR"。

## 开发流程

1. 决定路径：文档/状态/单文件微调可直推 `main`；其余创建 `feat|fix|refactor|docs|chore/<name>` 分支。
2. 先补充或确认测试，再实现功能。
3. 小步分批提交：一个提交一个主题，每个提交后仓库仍可构建；格式化与业务改动分离。
4. 同步更新文档、迁移和示例插件。
5. 提交前运行格式检查、单元测试、集成测试和启动检查。
6. 每完成一个任务或会话结束前 push 到 origin；复杂改动开 PR。

## 合并要求

- PR 描述包含：背景、需求编号、实现摘要、测试结果、数据库/配置变更和回滚方式。
- 不允许把密钥、真实数据或不可解释的生成物提交到仓库。
- 核心流程变更需要至少一次人工审查。
- 默认 merge commit 保留分批提交历史；仅单琐碎提交可 squash；禁止 force push `main`。
- P2/P3 缺陷、暂留想法、技术债和等待决策的问题先开 Issue（规则见 `docs/repository-maintenance.md` §8）。
