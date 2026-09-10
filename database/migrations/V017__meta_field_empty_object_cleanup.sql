-- P25 缺陷修复（live 视觉核验发现）：插件实体注册曾把"无 defaultValue/validation"
-- 写成 '{}'（JdbcLifecycleRepository orEmpty(null)），缺省值对象进入表单预填后
-- 渲染为 "[object Object]"（自 P08 潜伏）。写侧已改存 NULL，本迁移清理存量行；
-- 读侧（JdbcMetaRepository.parse）同步归一空对象为 null 兼容任何残留。
-- default_value/validation 为 text 列（存 JSON 文本），按字符串等值清理。
UPDATE meta_field SET default_value = NULL WHERE default_value = '{}';
UPDATE meta_field SET validation = NULL WHERE validation = '{}';
