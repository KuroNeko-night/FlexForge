package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.ProcessorArtifactRepository;
import com.flexforge.plugin.domain.ProcessorArtifactRepository.ArtifactRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/**
 * 处理器产物仓库（P23，FR-PLUGIN-14）：平台临时根（java.io.tmpdir 下）按
 * UUID 隔离的产物目录 + processor_artifact 行登记（归属/TTL/下载时间）；
 * 定时清理过期行与磁盘目录（TTL 10 分钟，docs/13 §3.5-5 唯一来源）。
 * 产物路径全部平台生成，无用户可控路径段（NFR-SEC-02）。
 */
@Component
public class ProcessorArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessorArtifactStore.class);

    static final Duration TTL = Duration.ofMinutes(10);

    private final ProcessorArtifactRepository repository;
    private final Clock clock;
    private final Path root;

    public ProcessorArtifactStore(ProcessorArtifactRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.root = Paths.get(System.getProperty("java.io.tmpdir"), "flexforge-artifacts");
    }

    /** 新产物目录（root/<uuid>，尚不存在——由 runner 迁移产物时创建）。 */
    public Path newArtifactDir(String artifactId) {
        return root.resolve(artifactId);
    }

    /** 登记入参（产物文件已由 runner 迁入 targetDir/<filename>）。 */
    public record Registration(String processorKey, String filename, String contentType,
                               long sizeBytes, Path targetDir) {
    }

    /** 登记产物行。 */
    public ArtifactRecord register(String artifactId, Registration registration, String actor) {
        ArtifactRecord record = new ArtifactRecord(artifactId, registration.processorKey(),
                registration.filename(), registration.contentType(),
                registration.sizeBytes(), registration.targetDir().toString(), actor,
                clock.instant(), clock.instant().plus(TTL), null);
        repository.insert(record);
        return record;
    }

    /** 行查找（null=不存在）。 */
    public ArtifactRecord find(String artifactId) {
        return repository.find(artifactId);
    }

    /** 产物文件路径（targetDir/<filename>；存在性由调用方校验）。 */
    public Path fileOf(ArtifactRecord record) {
        return Paths.get(record.storageDir()).resolve(record.filename());
    }

    public void markDownloaded(String artifactId) {
        repository.markDownloaded(artifactId, clock.instant());
    }

    /** 未使用的产物目录（输出非 file 契约时）尽力清理。 */
    public void discard(Path targetDir) {
        deleteDirectory(targetDir);
    }

    /** TTL 清理（5 分钟周期）：先删磁盘再删行（行残留可再清；反序会留孤儿文件）。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void sweepExpired() {
        for (ArtifactRecord expired : repository.expiredBefore(clock.instant())) {
            deleteDirectory(Paths.get(expired.storageDir()));
            repository.delete(expired.id());
        }
    }

    private static void deleteDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 尽力而为（被占用目录随进程生命周期回收）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 下载扩展名 → Content-Type（白名单闭集）。 */
    public static String contentTypeOf(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    /** 产物 ID 生成（无业务语义）。 */
    public static String newArtifactId() {
        return "pa-" + UUID.randomUUID();
    }
}
