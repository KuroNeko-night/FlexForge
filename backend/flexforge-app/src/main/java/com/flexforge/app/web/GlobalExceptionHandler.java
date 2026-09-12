package com.flexforge.app.web;

import com.flexforge.auth.AccountLockedException;
import com.flexforge.auth.InvalidCredentialsException;
import com.flexforge.auth.PermissionDeniedException;
import com.flexforge.common.RequestIds;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.api.ErrorResponse;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.StaleActivationException;
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

    /** IAE 消息回显口径：平台约定 IAE 只由各服务的入参校验抛出，消息是面向用户的
     * 业务文案（非内部诊断），可直接进响应体；框架/JDK 内部抛出的 IAE 同样走此
     * 映射，属已知边界（消息不包含堆栈/SQL，泄漏面有限）。 */
    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleValidation(IllegalArgumentException exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                exception.getMessage() == null ? "请求参数不合法" : exception.getMessage());
    }

    /** 插件包校验失败：携带稳定错误码（invalid_manifest/unsupported_schema_version/...）→ 400。 */
    @ExceptionHandler({PluginValidationException.class})
    public ResponseEntity<ErrorResponse> handlePluginValidation(PluginValidationException exception) {
        return envelope(HttpStatus.BAD_REQUEST, exception.code(), exception.getMessage());
    }

    /** 使用过期激活身份（FR-PLUGIN-07）：409 + stale_activation，不影响当前版本。 */
    @ExceptionHandler(StaleActivationException.class)
    public ResponseEntity<ErrorResponse> handleStaleActivation(StaleActivationException exception) {
        return envelope(HttpStatus.CONFLICT, ErrorCodes.STALE_ACTIVATION, exception.getMessage());
    }

    /** 模型访问失败（docs/09 P11）：503 + model_unavailable，任务未落状态可重试。 */
    @ExceptionHandler(com.flexforge.ai.model.ModelUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleModelUnavailable(
            com.flexforge.ai.model.ModelUnavailableException exception) {
        return envelope(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.MODEL_UNAVAILABLE,
                exception.getMessage());
    }

    /** 模型输出重试超限（RB-AI）：400 + model_output_invalid，可走手工规格。 */
    @ExceptionHandler(com.flexforge.ai.spec.ClarifyEngine.ModelOutputInvalidException.class)
    public ResponseEntity<ErrorResponse> handleModelOutputInvalid(
            com.flexforge.ai.spec.ClarifyEngine.ModelOutputInvalidException exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.MODEL_OUTPUT_INVALID,
                exception.getMessage());
    }

    /** Issue 非法状态迁移（FR-ISSUE-02）：400 + invalid_transition，可诊断。 */
    @ExceptionHandler(com.flexforge.issue.domain.InvalidTransitionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTransition(
            com.flexforge.issue.domain.InvalidTransitionException exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_TRANSITION,
                exception.getMessage());
    }

    /** 处理器执行失败（P20）：not_found→404 / 输入类失败→400 / 执行与输出失败→500；
     * P23 增 artifact_not_found→404 / artifact_expired→410（均带专用稳定码，审查 P2-5）。 */
    @ExceptionHandler(com.flexforge.plugin.domain.ProcessorExecutionException.class)
    public ResponseEntity<ErrorResponse> handleProcessorExecution(
            com.flexforge.plugin.domain.ProcessorExecutionException exception) {
        HttpStatus status;
        if (com.flexforge.common.api.ErrorCodes.PROCESSOR_NOT_FOUND.equals(exception.code())
                || com.flexforge.common.api.ErrorCodes.ARTIFACT_NOT_FOUND.equals(exception.code())) {
            status = HttpStatus.NOT_FOUND;
        } else if (com.flexforge.common.api.ErrorCodes.PROCESSOR_INPUT_TOO_LARGE.equals(
                        exception.code())
                || com.flexforge.common.api.ErrorCodes.PROCESSOR_INPUT_INVALID.equals(
                        exception.code())) {
            status = HttpStatus.BAD_REQUEST;
        } else if (com.flexforge.common.api.ErrorCodes.ARTIFACT_EXPIRED.equals(exception.code())) {
            status = HttpStatus.GONE;
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return envelope(status, exception.code(), exception.getMessage());
    }

    /** 上传超过 multipart 上限（docs/13 §3.5）：4xx 可诊断，不落 500 兜底。
     * P29 起上限场景含助手附件（请求总上限 35MB），文案保持场景中立。 */
    @ExceptionHandler({org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    public ResponseEntity<ErrorResponse> handleUploadSize(
            org.springframework.web.multipart.MaxUploadSizeExceededException exception) {
        return envelope(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "上传内容超过大小上限（单文件 10MB）");
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

    /** 自助注册触发 IP 限流（P13，docs/13 §3.1）：窗口后自动恢复，无需人工干预。 */
    @ExceptionHandler(com.flexforge.auth.RegisterRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleRegisterRateLimited(
            com.flexforge.auth.RegisterRateLimitedException exception) {
        return envelope(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED,
                exception.getMessage());
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
