package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 插件预设仓储（P21，FR-PLUGIN-12，docs/07 §1）：启用集合快照的落库与查询。
 * 应用（收敛编排）不在此处——由应用服务经既有生命周期端口执行。
 */
@PublicApi
public interface PresetRepository {

    /** 全量清单（按创建时间倒序，前端最近优先展示）。 */
    List<PresetRecord> listAll();

    Optional<PresetRecord> find(String id);

    /** 名称占用检查（应用层重名拒绝；DB 唯一约束兜底并发窗口）。 */
    boolean nameExists(String name);

    void insert(PresetRecord preset);

    void delete(String id);

    /** 单条预设：entries 为启用集合快照（每插件至多一条，取自当前占用）。 */
    @PublicApi
    record PresetRecord(String id, String name, List<PresetItem> entries,
                        String createdBy, Instant createdAt) {

        public PresetRecord {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** 快照条目：版本级恢复所需的最小字段。 */
    @PublicApi
    record PresetItem(String pluginId, String versionId, String version) {
    }
}
