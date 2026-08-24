package com.flexforge.data.domain;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.meta.domain.EntityDefinition;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 动态记录持久化端口（service.data-access 唯一存储路径，RB-DATA 单点断言目标）：
 * 记录表只允许本端口的 JDBC 实现访问；过滤/排序 SQL 仅由已校验的过滤条件与
 * PageQuery 白名单构造（NFR-SEC-02）。删除为物理删除。
 */
@PublicApi
public interface RecordRepository {

    /** 新增记录。 */
    RecordEntry insert(RecordEntry record);

    Optional<RecordEntry> find(String recordId);

    /**
     * 乐观覆盖记录 data（以读取时的 updatedAt 为前置条件，关闭并发丢失更新窗口）；
     * 返回受影响行数（0 = 不存在或已被并发修改）。
     */
    int updateData(String recordId, JsonNode data, Instant expectedUpdatedAt);

    /** 物理删除；返回受影响行数（0 = 不存在）。 */
    int delete(String recordId);

    /** 分页查询；filters 已白名单校验，page.sortBy 已在实体字段 ∪ 系统列白名单内。 */
    PageResult<RecordEntry> query(EntityDefinition entity, PageQuery page, List<RecordFilter> filters);
}
