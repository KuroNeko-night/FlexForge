package com.flexforge.common.audit;

import com.flexforge.common.PublicApi;

/**
 * 审计事件写入端口（service.audit，P02 定义 / P03 落库）：全部写路径模块通过本端口
 * 记录关键操作，不感知存储实现（docs/08 §2 domain port 分层）。
 */
@PublicApi
public interface AuditEventPort {

    /** 追加一条审计事件；实现必须非阻塞调用方主流程的可控失败（失败策略由 P03 落地时冻结）。 */
    void record(AuditEvent event);
}
