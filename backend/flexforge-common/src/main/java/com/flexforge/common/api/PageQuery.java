package com.flexforge.common.api;

import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;

import java.util.Objects;
import java.util.Set;

/**
 * 统一分页请求（docs/09 P02 契约冻结）：页码从 1 起；pageSize 默认 20、上限 200，
 * 超界在构造时拒绝；排序字段必须来自调用方提供的白名单，sortBy 为 null 表示不排序。
 * 页码上限 1..100000（P05 收紧：拦截 OFFSET int 溢出导致的 500，超界可诊断 400）。
 *
 * <p>口径（审计 P2-4）：{@link #of} 是唯一受信入口（白名单在此强制）；规范构造器只强制
 * 数值边界。P05 动态 SQL 构造处必须对 sortBy 二次强制白名单（防御纵深，docs/13 §4）。
 */
@PublicApi
public record PageQuery(int pageNumber, int pageSize, String sortBy, SortDirection sortDirection) {

    /** 页码硬上限（OFFSET 溢出防线）。 */
    public static final int MAX_PAGE_NUMBER = 100_000;

    /** 排序方向；默认 ASC。 */
    @PublicApi
    public enum SortDirection { ASC, DESC }

    public PageQuery {
        if (pageNumber < 1 || pageNumber > MAX_PAGE_NUMBER) {
            throw new IllegalArgumentException(
                    "pageNumber 必须在 1.." + MAX_PAGE_NUMBER + "，实际 " + pageNumber);
        }
        if (pageSize < 1 || pageSize > ApiConstants.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "pageSize 必须在 1.." + ApiConstants.MAX_PAGE_SIZE + "，实际 " + pageSize);
        }
    }

    /**
     * 白名单工厂（排序方向默认 ASC）：sortBy 非 null 时必须出现在 allowedSortFields 中。
     */
    public static PageQuery of(int pageNumber, int pageSize, String sortBy, Set<String> allowedSortFields) {
        return of(pageNumber, pageSize, sortBy, SortDirection.ASC, allowedSortFields);
    }

    /**
     * 白名单工厂：sortBy 非 null 时必须出现在 allowedSortFields 中，否则拒绝（防动态 SQL 注入面）。
     */
    public static PageQuery of(int pageNumber, int pageSize, String sortBy,
                               SortDirection sortDirection, Set<String> allowedSortFields) {
        PageQuery query = new PageQuery(pageNumber, pageSize, sortBy,
                sortBy == null ? SortDirection.ASC : Objects.requireNonNull(sortDirection, "sortDirection"));
        if (query.sortBy() != null
                && (allowedSortFields == null || !allowedSortFields.contains(query.sortBy()))) {
            throw new IllegalArgumentException("sortBy 不在白名单内: " + query.sortBy());
        }
        return query;
    }

    /** 默认首页（页码 1、默认页大小、不排序）。 */
    public static PageQuery firstPage() {
        return new PageQuery(1, ApiConstants.DEFAULT_PAGE_SIZE, null, SortDirection.ASC);
    }
}
