package com.flexforge.common.api;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 统一分页响应：items 为防御性快照（不可变），total 为满足条件的总记录数。
 */
@PublicApi
public record PageResult<T>(List<T> items, long total, int pageNumber, int pageSize) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
