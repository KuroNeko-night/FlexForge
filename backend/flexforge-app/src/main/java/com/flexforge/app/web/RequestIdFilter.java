package com.flexforge.app.web;

import com.flexforge.common.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * requestId 过滤器（docs/09 P02 契约）：优先透传请求头 X-Request-Id，否则生成
 * {@code req-} 前缀新值；写入 MDC 供日志模式输出、写入响应头回传客户端。
 *
 * <p>日志纪律（docs/13、R-GOV-08）：完成日志只含方法/路径/状态/requestId，
 * 永不记录请求头（含 Authorization）与查询串（可能含凭据）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 不记请求头与查询串，避免 Authorization/凭据泄漏（R-GOV-08）
            log.info("{} {} -> {} [{}]", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), requestId);
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming != null && incoming.matches("req-[0-9a-f]{8,64}")) {
            return incoming;
        }
        return RequestIds.newRequestId();
    }
}
