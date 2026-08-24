package com.flexforge.system;

import com.flexforge.common.PublicApi;
import com.flexforge.common.audit.AuditEvent;
import com.flexforge.common.audit.AuditEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * service.audit 写入端口落库（登记册 §2.1，P03）：追加写入 sys_audit_event，幂等由主键保证；
 * id 由写入端生成（UUID 字符串），载荷字段与登记册一一对应。
 *
 * <p>失败策略（P03 冻结，复审 P1-2）：审计写失败降级为 ERROR 日志（含 requestId 与事件字段，
 * 不含敏感载荷），不阻塞调用方主流程——登录等认证路径不能因审计库异常而 500。
 */
@PublicApi
@Component
public class JdbcAuditEventPort implements AuditEventPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditEventPort.class);

    private final JdbcTemplate jdbc;

    public JdbcAuditEventPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditEvent event) {
        try {
            jdbc.update("INSERT INTO sys_audit_event (id, actor, action, object_id, result, occurred_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    event.id(), event.actor(), event.action(), event.objectId(), event.result(),
                    Timestamp.from(event.occurredAt()));
        } catch (DataAccessException e) {
            // S8：日志不含 SQL/堆栈，只记异常类别摘要与事件字段
            log.error("审计写入失败 requestId={} action={} actor={} objectId={} result={} 原因={}",
                    MDC.get("requestId"), event.action(), event.actor(), event.objectId(),
                    event.result(), e.getClass().getSimpleName());
        }
    }
}
