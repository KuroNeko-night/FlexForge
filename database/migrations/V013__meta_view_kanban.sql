-- P17 看板视图：meta_view 扩 kanban 视图类型与 group_by 列（docs/09 P17）。
-- group_by 仅 kanban 视图使用（必填且指向同实体 enum 字段，业务校验在
-- ViewRules/写模型边界，此处只做存储与类型白名单约束）。
ALTER TABLE meta_view ADD COLUMN IF NOT EXISTS group_by VARCHAR(100);

ALTER TABLE meta_view DROP CONSTRAINT IF EXISTS ck_meta_view_type;
ALTER TABLE meta_view ADD CONSTRAINT ck_meta_view_type
    CHECK (view_type IN ('list', 'form', 'kanban'));
