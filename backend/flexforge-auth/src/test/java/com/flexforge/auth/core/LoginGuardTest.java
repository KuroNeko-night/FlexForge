package com.flexforge.auth.core;

import com.flexforge.auth.AuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录防暴破回归（docs/13 §3.1.2）：阈值锁定、锁定期拒绝、到期自动解锁、成功重置。
 */
class LoginGuardTest {

    /** 可推进的测试时钟（锁定到期为时间被动失效，需可控时间验证）。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-24T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void locksAfterConfiguredFailures() {
        MutableClock clock = new MutableClock();
        LoginGuard guard = guard(5, Duration.ofMinutes(10), clock);

        for (int i = 0; i < 4; i++) {
            assertThat(guard.onFailure("alice")).isEmpty();
        }
        Optional<Instant> lockedUntil = guard.onFailure("alice");

        assertThat(lockedUntil).contains(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(guard.isLocked("alice")).isTrue();
    }

    @Test
    void otherUsernamesAreIndependent() {
        LoginGuard guard = guard(5, Duration.ofMinutes(10), new MutableClock());

        for (int i = 0; i < 5; i++) {
            guard.onFailure("alice");
        }

        assertThat(guard.isLocked("alice")).isTrue();
        assertThat(guard.isLocked("bob")).isFalse();
    }

    @Test
    void lockExpiresAutomaticallyAfterDuration() {
        MutableClock clock = new MutableClock();
        LoginGuard guard = guard(1, Duration.ofMinutes(10), clock);

        guard.onFailure("carol");
        assertThat(guard.isLocked("carol")).isTrue();

        clock.advance(Duration.ofMinutes(11));
        assertThat(guard.isLocked("carol")).isFalse();
    }

    @Test
    void successResetsFailureCount() {
        LoginGuard guard = guard(5, Duration.ofMinutes(10), new MutableClock());

        for (int i = 0; i < 4; i++) {
            guard.onFailure("alice");
        }
        assertThat(guard.onSuccess("alice")).isTrue();

        // 重置后再失败 4 次仍不锁定
        for (int i = 0; i < 4; i++) {
            assertThat(guard.onFailure("alice")).isEmpty();
        }
        assertThat(guard.isLocked("alice")).isFalse();
    }

    @Test
    void partialFailuresExpireAndAreCollectedOnOtherUsersAccess() {
        MutableClock clock = new MutableClock();
        LoginGuard guard = guard(2, Duration.ofMinutes(10), clock);
        guard.onFailure("alice");
        clock.advance(Duration.ofMinutes(10));

        assertThat(guard.isLocked("bob")).isFalse();
        assertThat(guard.onSuccess("alice")).isFalse();
        assertThat(guard.onFailure("alice")).isEmpty();
    }

    @Test
    void capacityRejectsNewNamesWithoutEvictingLocksOrCounters() {
        MutableClock clock = new MutableClock();
        LoginGuard guard = guard(2, Duration.ofMinutes(10), clock);
        guard.onFailure("locked");
        guard.onFailure("locked");
        for (int i = 1; i < LoginGuard.MAX_TRACKED_USERNAMES; i++) {
            guard.onFailure("user-" + i);
        }

        assertThat(guard.isLocked("overflow")).isTrue();
        assertThat(guard.onFailure("overflow")).isPresent();
        assertThat(guard.onSuccess("overflow")).isFalse();
        assertThat(guard.isLocked("locked")).isTrue();
        assertThat(guard.onFailure("user-1")).isPresent();
        assertThat(guard.onSuccess("user-2")).isTrue();
        assertThat(guard.isLocked("overflow")).isFalse();
        assertThat(guard.onFailure("overflow")).isEmpty();

        clock.advance(Duration.ofMinutes(10));
        assertThat(guard.isLocked("new-user")).isFalse();
        assertThat(guard.onSuccess("locked")).isFalse();
        assertThat(guard.onSuccess("overflow")).isFalse();
    }

    @Test
    void repeatedFailuresDoNotExtendAnExistingLock() {
        MutableClock clock = new MutableClock();
        LoginGuard guard = guard(1, Duration.ofMinutes(10), clock);
        Instant until = guard.onFailure("alice").orElseThrow();
        clock.advance(Duration.ofMinutes(9));

        assertThat(guard.onFailure("alice")).contains(until);
        clock.advance(Duration.ofMinutes(1));
        assertThat(guard.isLocked("alice")).isFalse();
    }

    private static LoginGuard guard(int attempts, Duration lockDuration, Clock clock) {
        AuthProperties properties = new AuthProperties();
        properties.setLoginLockAttempts(attempts);
        properties.setLoginLockDuration(lockDuration);
        return new LoginGuard(properties, clock);
    }
}
