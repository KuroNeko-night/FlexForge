package com.flexforge.app.web;

import com.flexforge.auth.AccountLockedException;
import com.flexforge.auth.InvalidCredentialsException;
import com.flexforge.auth.PermissionDeniedException;
import com.flexforge.common.RequestIds;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

/**
 * 统一错误响应装配（docs/09 P02 验收：任一 API 错误都返回稳定错误码、消息与 requestId）。
 *
 * <p>口径（PR #12 子代理复审）：标准 4xx 异常类别显式映射，禁止落入 Exception 兜底
 * 被吞成 500；500 响应消息保持通用（细节只进服务端日志）；校验失败返回字段级摘要
 * 而非框架全转储。错误码只增不改（docs/08 §7 / 登记册 §2.4）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleValidation(IllegalArgumentException exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                exception.getMessage() == null ? "请求参数不合法" : exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            org.springframework.validation.BindException.class})
    public ResponseEntity<ErrorResponse> handleBinding(Exception exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, fieldSummary(exception));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "请求参数类型或格式不合法");
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNoResource(Exception exception) {
        return envelope(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "请求的资源不存在");
    }

    /** 登录失败统一消息（防用户枚举，docs/13 §3.1.3）与防暴破锁定（FR-AUTH 验收：可诊断）。 */
    @ExceptionHandler({InvalidCredentialsException.class, AccountLockedException.class})
    public ResponseEntity<ErrorResponse> handleAuthRejected(Exception exception) {
        return envelope(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, exception.getMessage());
    }

    /** 服务端角色授权拒绝（S2：@RequireRole 拦截器统一抛出）。 */
    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<ErrorResponse> handlePermissionDenied(PermissionDeniedException exception) {
        return envelope(HttpStatus.FORBIDDEN, ErrorCodes.PERMISSION_DENIED, exception.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException exception) {
        return envelope(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND,
                exception.getMessage() == null ? "资源不存在" : exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("未处理异常 [{}] {} {}", currentRequestId(), request.getMethod(),
                request.getRequestURI(), exception);
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR, "服务内部错误");
    }

    /** 字段级校验摘要（拒绝 BindingResult 全转储泄漏内部结构，复审 P2-1）。 */
    private static String fieldSummary(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException manve) {
            String summary = manve.getBindingResult().getFieldErrors().stream()
                    .map(error -> String.format("%s: %s", error.getField(),
                            error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage()))
                    .limit(5)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("请求参数不合法");
            return summary.length() > 300 ? summary.substring(0, 300) : summary;
        }
        return "请求参数不合法";
    }

    private static ResponseEntity<ErrorResponse> envelope(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, currentRequestId()));
    }

    private static String currentRequestId() {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return requestId != null ? requestId : RequestIds.newRequestId();
    }
}
