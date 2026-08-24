package com.flexforge.system;

import com.flexforge.common.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计落库失败策略回归（复审 P1-2，P03 冻结口径）：写失败降级 ERROR 日志，
 * 不得向调用方抛出——登录等主流程不能因审计库异常而失败。
 */
class JdbcAuditEventPortTest {

    @Test
    void recordSwallowsStorageFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("audit store down"));
        JdbcAuditEventPort port = new JdbcAuditEventPort(jdbc);

        AuditEvent event = new AuditEvent("audit-x", "tester", "auth.login", "u-1", "success",
                Instant.parse("2026-08-24T00:00:00Z"));

        assertThatCode(() -> port.record(event)).doesNotThrowAnyException();
    }
}
