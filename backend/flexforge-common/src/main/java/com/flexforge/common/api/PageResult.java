package com.flexforge.common.api;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 统一分页响应：items 为防御性快照（不可变），total 为满足条件的总记录数。
 */
@PublicApi
public record PageResult<T>(List<T> items, long total, int pageNumber, int pageSize) {

    public PageResult {
        // null 归一为空列表（查询无结果路径不必特判）；copyOf 给出不可变快照并顺带拒绝 null 元素
        items = items == null ? List.of() : List.copyOf(items);
    }
}
