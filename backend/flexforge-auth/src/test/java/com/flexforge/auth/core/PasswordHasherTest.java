package com.flexforge.auth.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BCrypt 慢哈希回归（docs/13 S3）：哈希可验证、错密码拒绝、同密码两次哈希不同（有盐）。
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashVerifiesRawPassword() {
        String hash = hasher.hash("correct-horse-battery");

        assertThat(hash).startsWith("$2");
        assertThat(hasher.matches("correct-horse-battery", hash)).isTrue();
    }

    @Test
    void wrongPasswordIsRejected() {
        String hash = hasher.hash("correct-horse-battery");

        assertThat(hasher.matches("wrong-password", hash)).isFalse();
        assertThat(hasher.matches("", hash)).isFalse();
    }

    @Test
    void saltedHashesDifferForSamePassword() {
        assertThat(hasher.hash("same")).isNotEqualTo(hasher.hash("same"));
    }
}
