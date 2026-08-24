package com.flexforge.auth.core;

import com.flexforge.common.PublicApi;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码慢哈希（docs/13 S3 / §3.1.1）：BCrypt cost 固定 10（红线最低值），哈希与盐由库托管，禁止自造实现。
 */
@PublicApi
public final class PasswordHasher {

    private static final int BCRYPT_COST = 10;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
