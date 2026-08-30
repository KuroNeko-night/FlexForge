package com.flexforge.auth.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.InvalidTokenException;
import com.flexforge.auth.core.JwtTokenService;
import com.flexforge.common.PublicApi;
import com.flexforge.common.RequestIds;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.api.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * JWT 认证过滤器（docs/09 P03）：/api/v1/auth/login 与非 API 路径放行（actuator 见 S9 只暴露 health），
 * 其余 /api/** 必须携带有效 Bearer 令牌；缺失/无效/过期返回 401 unauthorized 可诊断响应。
 * 认证主体写入请求属性 {@link #PRINCIPAL_ATTRIBUTE} 供控制器使用。
 *
 * <p>路径判定不使用原始 {@code getRequestURI()} 前缀（复审 P1-1：/api/v1/auth/login/../me
 * 可绕过），也不依赖容器的 servletPath 口径差异（MockMvc 中为空串）：自行 URL 解码、
 * 截断 {@code ;} 路径参数（Servlet 映射语义忽略它们）并消解 ./.. 段后精确判定；
 * 解码失败、反斜杠或根目录逃逸一律 400。注意 URLDecoder 会把 + 解码为空格（表单语义，
 * 比容器路径解码更严），本 API 路径不含 +，方向上只会拒绝不会放行。
 */
@PublicApi
@Component
// RequestIdFilter 占 HIGHEST_PRECEDENCE，本过滤器紧随其后：401 响应体里的 requestId 依赖其先写入 MDC
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String PRINCIPAL_ATTRIBUTE = "flexforge.auth.principal";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String BEARER_PREFIX = "Bearer ";
    /** 资产 serve 路径（P12.5）：CSS url()/裸 fetch 无法携带 Bearer，匿名放行到控制器，
     * 由其校验短期 HMAC 签名（docs/09 P12.5、PR #32 审查 P1）；仅匹配 /assets/ 段。 */
    private static final java.util.regex.Pattern ANONYMOUS_ASSET_PATH = java.util.regex.Pattern
            .compile("^/api/v1/plugins/activations/[^/]+/assets/.+$");

    private final JwtTokenService tokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path;
        try {
            path = normalizePath(request);
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                    "请求路径不合法");
            return;
        }
        if (!path.startsWith("/api/") || LOGIN_PATH.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (ANONYMOUS_ASSET_PATH.matcher(path).matches()) {
            // 匿名放行到资产控制器（验签在控制器）；携带有效令牌时仍附主体走原语义
            try {
                request.setAttribute(PRINCIPAL_ATTRIBUTE, parsePrincipal(request));
            } catch (InvalidTokenException e) {
                /* 无效/缺失令牌按匿名处理（PR #32 审查 P1） */
            }
            filterChain.doFilter(request, response);
            return;
        }
        AuthPrincipal principal;
        try {
            principal = parsePrincipal(request);
        } catch (InvalidTokenException e) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                    e.getMessage());
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        filterChain.doFilter(request, response);
    }

    /** 解码并消解路径段；非法编码/反斜杠/根目录逃逸抛 IllegalArgumentException。 */
    static String normalizePath(HttpServletRequest request) {
        return "/" + String.join("/", resolveSegments(decodeRawPath(request)));
    }

    private static String decodeRawPath(HttpServletRequest request) {
        String raw = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && raw.startsWith(context)) {
            raw = raw.substring(context.length());
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad url encoding");
        }
        if (decoded.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("backslash in path");
        }
        return decoded;
    }

    private static List<String> resolveSegments(String decodedPath) {
        Deque<String> segments = new ArrayDeque<>();
        for (String rawSegment : decodedPath.split("/")) {
            // Servlet 语义：路径参数（;... 至下一个 /）不参与映射，容器会先剥离——
            // 过滤器侧同样截断，否则 /;x/api/v1/auth/me 会因不以 /api/ 开头被放行（复审 P1）
            String segment = truncatePathParams(rawSegment);
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("path escapes root");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return List.copyOf(segments);
    }

    private static String truncatePathParams(String segment) {
        int semicolon = segment.indexOf(';');
        return semicolon < 0 ? segment : segment.substring(0, semicolon);
    }

    private AuthPrincipal parsePrincipal(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            throw InvalidTokenException.missing();
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            throw InvalidTokenException.malformed();
        }
        return tokenService.parse(header.substring(BEARER_PREFIX.length()));
    }

    private void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        Objects.requireNonNull(message, "message");
        String requestId = MDC.get("requestId");
        ErrorResponse body = ErrorResponse.of(code, message,
                requestId == null ? RequestIds.newRequestId() : requestId);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
