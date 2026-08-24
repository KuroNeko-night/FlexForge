package com.flexforge.auth.api;

import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.core.JwtTokenService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 认证放行判定回归（复审 P1-1）：路径先解码并消解 ./.. 段再判定，
 * login 仅精确等值放行；非法编码/根目录逃逸/反斜杠直接 400。
 */
class JwtAuthFilterPathTest {

    private JwtAuthFilter filter() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("test-hmac-material-0123456789abcdef");
        return new JwtAuthFilter(new JwtTokenService(properties, Clock.systemUTC()),
                JsonMapper.builder().build());
    }

    private record Result(int status, AtomicInteger chainCalls) {
    }

    private Result run(String method, String rawUri, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, rawUri);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();
        FilterChain chain = (req, res) -> chainCalls.incrementAndGet();
        filter().doFilter(request, response, chain);
        return new Result(response.getStatus(), chainCalls);
    }

    @Test
    void loginPrefixTraversalIsNormalizedToProtectedPath() throws Exception {
        Result result = run("GET", "/api/v1/auth/login/../me", null);

        assertThat(result.chainCalls().get()).as("不得放行进入业务链").isZero();
        assertThat(result.status()).isEqualTo(401);
    }

    @Test
    void multiLevelAndEncodedTraversalAreNormalized() throws Exception {
        assertThat(run("GET", "/api/v1/auth/login/../../v1/auth/me", null).status()).isEqualTo(401);
        assertThat(run("GET", "/api/v1/foo/..;/auth/me", null).status()).isEqualTo(401);
    }

    @Test
    void exactLoginPathIsPermitted() throws Exception {
        Result result = run("POST", "/api/v1/auth/login", null);

        assertThat(result.chainCalls().get()).isEqualTo(1);
    }

    @Test
    void loginPathWithSuffixRequiresAuthenticationButTrailingDotNormalizesToLogin() throws Exception {
        assertThat(run("POST", "/api/v1/auth/login/extra", null).status()).isEqualTo(401);
        // 尾部 "." 消解后即 login 本身，与容器规范化语义一致，应放行
        assertThat(run("POST", "/api/v1/auth/login/.", null).chainCalls().get()).isEqualTo(1);
    }

    @Test
    void nonApiPathIsPermitted() throws Exception {
        assertThat(run("GET", "/actuator/health", null).chainCalls().get()).isEqualTo(1);
    }

    @Test
    void rootEscapeAndBadEncodingAreRejectedAsBadRequest() throws Exception {
        assertThat(run("GET", "/api/../..%2fsecret", null).status()).isEqualTo(400);
        assertThat(run("GET", "/api/v1/%", null).status()).isEqualTo(400);
        assertThat(run("GET", "/api/v1/a%5cb", null).status()).isEqualTo(400);
    }

    @Test
    void validTokenPassesNormalizedProtectedPath() throws Exception {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("test-hmac-material-0123456789abcdef");
        JwtTokenService tokens = new JwtTokenService(properties, Clock.systemUTC());
        String token = tokens.issue(7L, List.of("USER"), Duration.ofMinutes(5)).token();

        Result result = run("GET", "/api/v1/auth/me", token);

        assertThat(result.chainCalls().get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(200);
    }

    @Test
    void normalizePathRejectsRootEscapeDirectly() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/../etc/passwd");
        assertThatThrownBy(() -> JwtAuthFilter.normalizePath(request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
