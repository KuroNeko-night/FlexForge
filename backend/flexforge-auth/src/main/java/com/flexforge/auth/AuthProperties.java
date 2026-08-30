package com.flexforge.auth;

import com.flexforge.common.PublicApi;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 认证配置（flexforge.auth.*）：JWT 密钥/TTL、防暴破参数与引导管理员（docs/13 §3.1、§4 P03）。
 */
@PublicApi
@ConfigurationProperties(prefix = "flexforge.auth")
public class AuthProperties {

    /** JWT HS256 密钥（>= 256bit，仅环境变量注入；空值或过短在 JwtTokenService 构造时 fail-fast）。 */
    private String jwtSecret = "";

    /** JWT 有效期；演示环境需覆盖完整演示时长（docs/09 P03）。 */
    private Duration jwtTtl = Duration.ofHours(8);

    /** 引导管理员用户名（仅 sys_user 为空时使用）。 */
    private String bootstrapAdminUsername = "admin";

    /** 引导管理员密码（仅环境变量注入；为空则不引导）。 */
    private String bootstrapAdminPassword = "";

    /** 连续失败锁定阈值。 */
    private int loginLockAttempts = 5;

    /** 锁定时长。 */
    private Duration loginLockDuration = Duration.ofMinutes(10);

    /** 自助注册开关（P13 feature flag，docs/09 P13）：关闭时 /auth/register 拒绝且前端隐藏入口。 */
    private boolean selfRegistrationEnabled = true;

    /** 自助注册单 IP 窗口内次数上限（内存口径，docs/13 §3.1）。 */
    private int registerRateLimit = 5;

    /** 自助注册限流窗口。 */
    private Duration registerRateWindow = Duration.ofHours(1);

    public boolean isSelfRegistrationEnabled() {
        return selfRegistrationEnabled;
    }

    public void setSelfRegistrationEnabled(boolean selfRegistrationEnabled) {
        this.selfRegistrationEnabled = selfRegistrationEnabled;
    }

    public int getRegisterRateLimit() {
        return registerRateLimit;
    }

    public void setRegisterRateLimit(int registerRateLimit) {
        this.registerRateLimit = registerRateLimit;
    }

    public Duration getRegisterRateWindow() {
        return registerRateWindow;
    }

    public void setRegisterRateWindow(Duration registerRateWindow) {
        this.registerRateWindow = registerRateWindow;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getJwtTtl() {
        return jwtTtl;
    }

    public void setJwtTtl(Duration jwtTtl) {
        this.jwtTtl = jwtTtl;
    }

    public String getBootstrapAdminUsername() {
        return bootstrapAdminUsername;
    }

    public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
        this.bootstrapAdminUsername = bootstrapAdminUsername;
    }

    public String getBootstrapAdminPassword() {
        return bootstrapAdminPassword;
    }

    public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    public int getLoginLockAttempts() {
        return loginLockAttempts;
    }

    public void setLoginLockAttempts(int loginLockAttempts) {
        this.loginLockAttempts = loginLockAttempts;
    }

    public Duration getLoginLockDuration() {
        return loginLockDuration;
    }

    public void setLoginLockDuration(Duration loginLockDuration) {
        this.loginLockDuration = loginLockDuration;
    }
}
