package com.flexforge.common.contract;

import com.flexforge.common.PublicApi;

/**
 * 运行时注册项句柄（docs/extension-points.md §1.3）：每个注册项绑定 activationId，
 * 停用/卸载时通过 {@link #close()} 撤销全部贡献（FR-PLUGIN-06、NFR-PLUGIN-01）。
 *
 * <p>契约：close 幂等（重复关闭不抛异常）；close 后 isActive 为 false 且贡献不可见。
 */
@PublicApi
public interface Registration {

    /** 该注册项所属的激活身份；平台内置注册使用显式哨兵值（如 "platform"）。 */
    String activationId();

    /** 是否仍处于激活状态。 */
    boolean isActive();

    /** 撤销本次注册；幂等，线程安全。 */
    void close();
}
