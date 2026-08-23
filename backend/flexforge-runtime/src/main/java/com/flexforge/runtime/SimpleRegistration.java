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
        if (active.compareAndSet(true, false)) {
            disposeAction.run();
        }
    }
}
