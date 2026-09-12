package com.flexforge.kb.domain;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 知识库条目仓储（V018 kb_entry，FR-KB-01）：写操作仅经 ADMIN 服务路径
 * （S2 服务端收口），本端口不做权限判断。
 */
@PublicApi
public interface KbEntryRepository {

    /** 全量条目（updated_at 倒序，上限 200——FR-KB-01 列表口径）。 */
    List<KbEntryRecord> listAll();

    KbEntryRecord find(String id);

    KbEntryRecord insert(KbEntryRecord entry);

    KbEntryRecord update(KbEntryRecord entry);

    void delete(String id);

    /** 知识条目（content 为纯文本正文；时间戳由仓储落库）。 */
    @PublicApi
    record KbEntryRecord(String id, String title, String category, String content,
                         String createdBy, String updatedAt) {
    }
}
