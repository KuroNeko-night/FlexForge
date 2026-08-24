package com.flexforge.common.audit;

import com.flexforge.common.PublicApi;

/**
 * 审计事件写入端口（service.audit，P02 定义 / P03 落库）：全部写路径模块通过本端口
 * 记录关键操作，不感知存储实现（docs/08 §2 domain port 分层）。
 *
 * <p>失败策略（P03 冻结）：实现必须把写失败降级为日志，不得阻塞调用方主流程
 * （例如登录成功不得因审计库异常而失败）。
 */
@PublicApi
public interface AuditEventPort {

    /** 追加一条审计事件；实现内部消化写失败（降级 ERROR 日志），不向调用方抛出。 */
    void record(AuditEvent event);
}
