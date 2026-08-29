package com.flexforge.common.api;

import com.flexforge.common.PublicApi;

/**
 * 稳定错误码集合（docs/extension-points.md §2.4 / docs/08 §7 基线）：只增、不改名、不删；
 * 删除或改名是 breaking，需要 ADR。客户端必须容忍未知错误码。
 */
@PublicApi
public final class ErrorCodes {

    /** 插件清单不合法或不可解析。 */
    public static final String INVALID_MANIFEST = "invalid_manifest";

    /** 插件清单 schemaVersion 不受支持（P07：schemaVersion 分派层拒绝）。 */
    public static final String UNSUPPORTED_SCHEMA_VERSION = "unsupported_schema_version";

    /** 插件依赖缺失或版本不满足。 */
    public static final String DEPENDENCY_MISSING = "dependency_missing";

    /** 插件数据库迁移执行失败。 */
    public static final String MIGRATION_FAILED = "migration_failed";

    /** 运行时注册（服务/扩展点/监听器）失败。 */
    public static final String REGISTRATION_FAILED = "registration_failed";

    /** activationId 不存在。 */
    public static final String ACTIVATION_NOT_FOUND = "activation_not_found";

    /** 使用过期激活身份的请求被拒绝。 */
    public static final String STALE_ACTIVATION = "stale_activation";
    /** P10：Issue 非法状态迁移/缺原因/缺合法规格批准门（docs/08 §7 只增）。 */
    public static final String INVALID_TRANSITION = "invalid_transition";
    /** P11：模型访问失败（超时/取消/限流/离线，docs/09 P11 验收）。 */
    public static final String MODEL_UNAVAILABLE = "model_unavailable";
    /** P11：模型输出经有限重试仍不合法（非法 JSON/Schema 违约）。 */
    public static final String MODEL_OUTPUT_INVALID = "model_output_invalid";

    /** 权限不足。 */
    public static final String PERMISSION_DENIED = "permission_denied";

    /** 请求参数校验失败（含分页白名单、非法枚举等 API 边界拒绝）。 */
    public static final String VALIDATION_ERROR = "validation_error";

    /** 请求的资源不存在。 */
    public static final String NOT_FOUND = "not_found";

    /** 服务端内部错误（响应消息保持通用，细节只进日志）。 */
    public static final String INTERNAL_ERROR = "internal_error";

    /** 未认证：缺失/无效/过期的登录令牌（消息细分可诊断，docs/09 P03）。 */
    public static final String UNAUTHORIZED = "unauthorized";

    private ErrorCodes() {
    }
}
