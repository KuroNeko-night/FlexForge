-- P25 缺陷修复（live 视觉核验发现）：插件实体注册曾把"无 defaultValue"写成
-- '{}'（JdbcLifecycleRepository orEmpty(null)），缺省值对象进入表单预填后渲染为
-- "[object Object]"（自 P08 潜伏）。写侧已改存 NULL（default_value 列可空），
-- 本迁移清理存量行。validation 列 NOT NULL，其 '{}' 由读侧 parse 归一为 null
-- （JdbcMetaRepository），不做数据变更。
-- default_value 为 TEXT 列（存 JSON 文本），按字符串等值清理。
UPDATE meta_field SET default_value = NULL WHERE default_value = '{}';
