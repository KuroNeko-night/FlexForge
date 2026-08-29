package com.flexforge.runtime;

import com.flexforge.common.contract.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可复用的幂等注册句柄：close 只在首次调用时执行 disposeAction，
 * 之后 close 为无操作（Registration 契约：重复关闭不抛异常）。
 */
final class SimpleRegistration implements Registration {

    private final String activationId;
    private final Runnable disposeAction;
    private final AtomicBoolean active = new AtomicBoolean(true);

    SimpleRegistration(String activationId, Runnable disposeAction) {
        this.activationId = Objects.requireNonNull(activationId, "activationId");
        this.disposeAction = Objects.requireNonNull(disposeAction, "disposeAction");
    }

    @Override
    public String activationId() {
        return activationId;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void close() {
        // 先 CAS 置非活跃再执行 dispose：注册表监视器依赖"dispose 运行时自身必已非活跃"
        // 这一次序，才能区分"自身已关闭待清理"与"条目已被新注册覆盖"两种情形
        if (active.compareAndSet(true, false)) {
            disposeAction.run();
        }
    }
}
