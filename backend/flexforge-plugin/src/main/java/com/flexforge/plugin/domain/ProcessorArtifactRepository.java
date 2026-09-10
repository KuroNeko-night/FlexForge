package com.flexforge.plugin.domain;

import com.flexforge.common.PublicApi;

import java.time.Instant;
import java.util.List;

/**
 * 处理器产物持久化端口（P23，FR-PLUGIN-14，processor_artifact 表）：
 * 临时产物登记（归属+TTL）与过期清理的数据面。
 */
@PublicApi
public interface ProcessorArtifactRepository {

    void insert(ArtifactRecord record);

    /** 行不存在返回 null（防枚举：无权与不存在同码由上层统一）。 */
    ArtifactRecord find(String artifactId);

    /** 记录首次下载时间（已下载不覆盖）。 */
    void markDownloaded(String artifactId, Instant at);

    /** 取回已过期行（清理用：行与磁盘目录一并删除）。 */
    List<ArtifactRecord> expiredBefore(Instant now);

    void delete(String artifactId);

    /** 产物登记行（storage_dir 为平台生成的持久目录，无用户可控路径段）。 */
    @PublicApi
    record ArtifactRecord(String id, String processorKey, String filename, String contentType,
                          long sizeBytes, String storageDir, String createdBy,
                          Instant createdAt, Instant expiresAt, Instant downloadedAt) {
    }
}
