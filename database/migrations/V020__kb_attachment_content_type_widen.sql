-- P29 live 走查修复：Office 开放 XML MIME（如
-- application/vnd.openxmlformats-officedocument.wordprocessingml.document，71 字符）
-- 超出 V019 的 VARCHAR(64) 触发 CHECK 后 500。拓宽到 255（服务端另有截断防御）。
ALTER TABLE kb_attachment ALTER COLUMN content_type TYPE VARCHAR(255);
