package com.flexforge.auth;

import com.flexforge.auth.api.RoleAuthorizationInterceptor;
import com.flexforge.auth.core.AuthKernel;
import com.flexforge.auth.core.JwtTokenService;
import com.flexforge.auth.core.LoginGuard;
import com.flexforge.auth.core.PasswordHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Clock;

/**
 * auth 模块装配：属性绑定 + UTC 时钟 + 核心服务 + 角色授权拦截器（由 app 组件扫描引入）。
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig implements WebMvcConfigurer {

    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor =
            new RoleAuthorizationInterceptor();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleAuthorizationInterceptor);
    }

    @Bean
    public Clock systemUtcClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new PasswordHasher();
    }

    @Bean
    public JwtTokenService jwtTokenService(AuthProperties properties, Clock clock) {
        return new JwtTokenService(properties, clock);
    }

    @Bean
    public LoginGuard loginGuard(AuthProperties properties, Clock clock) {
        return new LoginGuard(properties, clock);
    }

    /** 内核聚合（AuthService 依赖收敛，§7 参数 ≤5）。 */
    @Bean
    public AuthKernel authKernel(PasswordHasher hasher, JwtTokenService tokens,
                                 LoginGuard guard, AuthProperties properties) {
        return new AuthKernel(hasher, tokens, guard, properties);
    }
}
