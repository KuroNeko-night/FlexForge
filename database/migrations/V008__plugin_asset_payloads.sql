-- P08 迭代 2：插件静态资产存储（theme-asset serve 端点支撑，Issue #20 第 3/4 项）。
-- 导入时按 contributions.themeAssets 声明逐文件存 base64；同 metadata/script 载荷一样
-- 属 plugin_version 不可变内容（contentHash 覆盖整个包）。
ALTER TABLE plugin_version ADD COLUMN IF NOT EXISTS asset_payloads JSONB NOT NULL DEFAULT '{}'::jsonb;
