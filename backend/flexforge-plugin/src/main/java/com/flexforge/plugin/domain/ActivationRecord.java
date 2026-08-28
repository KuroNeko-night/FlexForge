package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;

/** plugin_activation 行镜像。 */
@PublicApi
public record ActivationRecord(
        String id,
        String pluginId,
        String pluginVersionId,
        String operation,
        ActivationStatus status,
        String stage,
        String errorCode,
        String requestedBy,
        Instant startedAt,
        Instant finishedAt) {
}
