-- V003：平台角色种子（管理员/开发者/普通用户，docs/02 §1）。
-- 平台骨架数据而非业务数据（ADR-0004：业务菜单/实体/角色语义不得进入平台迁移）。
-- 幂等：重复执行不产生重复行。

INSERT INTO sys_role (code, name) VALUES
    ('ADMIN', '管理员'),
    ('DEVELOPER', '开发者'),
    ('USER', '普通用户')
ON CONFLICT (code) DO NOTHING;
