-- P09 前置守卫（Issue #22 第 1 项）：同插件同刻唯一 STARTING/ACTIVE 激活的
-- 数据库级兜底——应用层 findOccupying 检查-后-插入存在并发窗口，两个管理员
-- 并发激活同插件不同版本可产生双 ACTIVE（违反 docs/07 §3）。部分唯一索引
-- 使第二个插入直接失败；应用层捕获唯一约束冲突后复查幂等口径。
-- 注：若在已存在双占用重复行的存量库上执行，CREATE UNIQUE INDEX 会失败，
-- 需先人工保留每个 plugin_id 最新一条 STARTING/ACTIVE 再重跑迁移
-- （本项目各环境均为一次性 Testcontainers/干净库，无存量数据）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_plugin_activation_occupancy
    ON plugin_activation (plugin_id)
    WHERE status IN ('STARTING', 'ACTIVE');
