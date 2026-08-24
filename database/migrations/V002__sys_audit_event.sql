-- V002：审计事件表（service.audit 落库，P03）。载荷与登记册 §2.1 service.audit 一致；
-- id 由写入端生成（UUID 字符串），查询接口（按时间/操作者/对象过滤）P03 迭代 2 落地。

CREATE TABLE IF NOT EXISTS sys_audit_event (
    id          VARCHAR(64)  PRIMARY KEY,
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    object_id   VARCHAR(128) NOT NULL,
    result      VARCHAR(16)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sys_audit_event_occurred ON sys_audit_event (occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_sys_audit_event_actor ON sys_audit_event (actor);
CREATE INDEX IF NOT EXISTS idx_sys_audit_event_action ON sys_audit_event (action);

COMMENT ON TABLE sys_audit_event IS '平台审计事件（P03；登录/登出/锁定/权限变化等关键操作）';
