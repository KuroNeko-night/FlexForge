package com.flexforge.common;

/**
 * 平台级公开契约常量，唯一来源为本类（docs/10 R3：单一实现路径）。
 *
 * <p>数值口径以 docs/09 P02 冻结的契约为准：API 前缀 /api/v1，分页默认 20、上限 200。
 * 新增常量为 additive；改名、删除或改值均为 breaking，需要 ADR（docs/10 §4）。
 */
@PublicApi
public final class ApiConstants {

    /** REST API 统一版本前缀（docs/extension-points.md §2.4 受控契约）。 */
    public static final String API_V1 = "/api/v1";

    /** 分页默认页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 分页页大小硬上限，超出必须在 API 边界拒绝。 */
    public static final int MAX_PAGE_SIZE = 200;

    private ApiConstants() {
    }
}
