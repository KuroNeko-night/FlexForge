package com.flexforge.auth.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 认证接口（FR-AUTH-01/02）：登录换取 JWT、退出（审计）、当前用户资料。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    public record UserInfo(long id, String username, String displayName, List<String> roles) {
    }

    public record LoginResponse(String token, String tokenType, Instant expiresAt, UserInfo user) {
    }

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new IllegalArgumentException("username 与 password 必填");
        }
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        UserInfo user = new UserInfo(result.user().id(), result.user().username(),
                result.user().displayName(), result.user().roles());
        return new LoginResponse(result.token(), "Bearer", result.expiresAt(), user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@org.springframework.web.bind.annotation.RequestAttribute(
            JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        authService.logout(principal);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserInfo me(@org.springframework.web.bind.annotation.RequestAttribute(
            JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal) {
        var user = authService.currentUser(principal);
        return new UserInfo(user.id(), user.username(), user.displayName(), user.roles());
    }
}
