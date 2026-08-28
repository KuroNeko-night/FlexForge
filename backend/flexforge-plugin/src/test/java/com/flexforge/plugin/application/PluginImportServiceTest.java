package com.flexforge.plugin.application;

import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.plugin.domain.InstallPreview;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginVersionRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发导入幂等复查（PR #19 审查 P1-4：唯一约束冲突后按 content hash 复查）。 */
class PluginImportServiceTest {

    private static final Map<String, String> NO_SCRIPTS = Map.of();

    static byte[] minimalPackage() {
        String manifest = "{\"schemaVersion\":1,\"id\":\"demo.race\",\"name\":\"race\","
                + "\"version\":\"1.0.0\",\"capabilityLevel\":1,\"minPlatformVersion\":\"0.1.0\","
                + "\"dependencies\":[],\"contributions\":{},"
                + "\"resources\":{\"entities\":[],\"views\":[],\"migrations\":[]}}";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", manifest.getBytes(StandardCharsets.UTF_8));
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PluginVersionRecord record(String hash) {
        return new PluginVersionRecord("pv-winner", "demo.race", "1.0.0", hash, 1,
                "{\"name\":\"race\"}", NO_SCRIPTS, 100, null);
    }

    /** 并发窗口：storeVersion 撞唯一约束 → 复查 content hash 命中 → 幂等返回。 */
    @Test
    void concurrentDuplicateStoreFallsBackToIdempotentHit() throws IOException {
        AtomicInteger hashLookups = new AtomicInteger();
        AtomicInteger stores = new AtomicInteger();
        PluginPackageRepository repository = new PluginPackageRepository() {
            @Override
            public Optional<PluginVersionRecord> findByContentHash(String contentHash) {
                // 第一次（导入开头）未命中；冲突复查时命中赢家
                return hashLookups.incrementAndGet() == 1
                        ? Optional.empty()
                        : Optional.of(record(contentHash));
            }

            @Override
            public Optional<PluginVersionRecord> findVersion(String pluginId, String version) {
                return Optional.empty();
            }

            @Override
            public List<String> versionsOf(String pluginId) {
                return List.of();
            }

            @Override
            public PluginVersionRecord storeVersion(String pluginName, PluginVersionRecord version,
                                                    List<com.flexforge.plugin.domain.DependencySpec> deps) {
                stores.incrementAndGet();
                throw new DuplicateKeyException("uq_plugin_version_hash");
            }
        };
        PluginImportService service = new PluginImportService(
                new PluginImportKernel(new ArchiveInspector(), new ManifestValidator(),
                        new MigrationScriptScanner(), new DependencyResolver()),
                repository, audit -> { }, Clock.systemUTC());

        InstallPreview preview = service.importPackage("tester", minimalPackage());

        assertThat(preview.isNew()).isFalse();
        assertThat(preview.versionId()).isEqualTo("pv-winner");
        assertThat(stores.get()).isEqualTo(1);
    }

    /** 并发同版本异内容：hash 复查 miss → 按同版本冲突转 400（非 500）。 */
    @Test
    void concurrentSameVersionDifferentContentRejectedAsConflict() {
        PluginPackageRepository repository = new PluginPackageRepository() {
            @Override
            public Optional<PluginVersionRecord> findByContentHash(String contentHash) {
                return Optional.empty(); // 两次均未命中（内容不同）
            }

            @Override
            public Optional<PluginVersionRecord> findVersion(String pluginId, String version) {
                return Optional.of(record("another-hash")); // 赢家已占同 (id, version)
            }

            @Override
            public List<String> versionsOf(String pluginId) {
                return List.of();
            }

            @Override
            public PluginVersionRecord storeVersion(String pluginName, PluginVersionRecord version,
                                                    List<com.flexforge.plugin.domain.DependencySpec> deps) {
                throw new DuplicateKeyException("uq_plugin_version_natural");
            }
        };
        PluginImportService service = new PluginImportService(
                new PluginImportKernel(new ArchiveInspector(), new ManifestValidator(),
                        new MigrationScriptScanner(), new DependencyResolver()),
                repository, event -> { }, Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.importPackage("tester", minimalPackage()))
                .isInstanceOf(com.flexforge.plugin.domain.PluginValidationException.class)
                .hasMessageContaining("不一致");
    }
}
