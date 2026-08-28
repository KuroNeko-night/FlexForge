package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.plugin.domain.InstallPreview;
import com.flexforge.plugin.domain.PluginManifest;
import com.flexforge.plugin.domain.PluginPackageRepository;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.PluginVersionRecord;
import com.flexforge.plugin.domain.ValidationReport;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 插件包校验与幂等导入（docs/09 P07）：安全解包 → manifest 解析/校验
 * （schemaVersion 分派）→ 声明核对 → 迁移脚本校验与 checksum → 依赖解析 →
 * content hash 幂等（同内容返回既有版本）→ 同版本异内容拒绝 → 事务化落库
 * + 审计（plugin.import）。
 */
@PublicApi
@Service
public class PluginImportService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 协作者内核（参数上限口径：校验管线四件套 + 存储端口/审计/时钟）。 */
    private final PluginImportKernel kernel;
    private final PluginPackageRepository repository;
    private final AuditEventPort audit;
    private final Clock clock;

    public PluginImportService(PluginImportKernel kernel, PluginPackageRepository repository,
                               AuditEventPort audit, Clock clock) {
        this.kernel = kernel;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    /** 校验端点：全链路校验、不落库；失败逐条返回可诊断 findings。 */
    public ValidationReport validate(byte[] zipBytes) throws java.io.IOException {
        try (SafeArchive archive = kernel.inspector().inspect(zipBytes)) {
            PluginManifest manifest = inspectManifest(archive);
            Map<String, String> checksums = kernel.scanner().scan(
                    manifest.resources().migrations(), archive::fileBytes);
            kernel.resolver().resolve(manifest.dependencies(), repository::versionsOf);
            return ValidationReport.ok(transientPreview(manifest,
                    sha256(zipBytes), checksums));
        } catch (PluginValidationException e) {
            return ValidationReport.failed(List.of(e.code() + ": " + e.getMessage()));
        }
    }

    /** 导入端点：幂等（content hash 命中即返回既有版本，不重复落库）。 */
    public InstallPreview importPackage(String actor, byte[] zipBytes) throws java.io.IOException {
        String hash = sha256(zipBytes);
        var existing = repository.findByContentHash(hash);
        if (existing.isPresent()) {
            return previewOf(existing.orElseThrow(), false);
        }
        try (SafeArchive archive = kernel.inspector().inspect(zipBytes)) {
            PluginManifest manifest = inspectManifest(archive);
            Map<String, String> checksums = kernel.scanner().scan(
                    manifest.resources().migrations(), archive::fileBytes);
            kernel.resolver().resolve(manifest.dependencies(), repository::versionsOf);
            requireNoVersionConflict(manifest, hash);
            PluginVersionRecord candidate = recordOf(manifest, hash, zipBytes.length, checksums);
            PluginVersionRecord stored = storeIdempotently(manifest.name(), candidate,
                    manifest.dependencies(), hash);
            audit.record(AuditEvents.of(actor, "plugin.import", stored.id(), "success", clock));
            return previewOf(stored, stored.id().equals(candidate.id()));
        }
    }

    /** 并发窗口兜底：唯一约束冲突后复查 content hash，命中即幂等返回（接口契约）。 */
    private PluginVersionRecord storeIdempotently(String pluginName, PluginVersionRecord candidate,
                                                  List<com.flexforge.plugin.domain.DependencySpec> dependencies,
                                                  String hash) {
        try {
            return repository.storeVersion(pluginName, candidate, dependencies);
        } catch (DuplicateKeyException e) {
            var winner = repository.findByContentHash(hash);
            if (winner.isPresent()) {
                return winner.orElseThrow();
            }
            throw e;
        }
    }

    private PluginManifest inspectManifest(SafeArchive archive) {
        JsonNode root = ManifestParser.parse(archive.manifestBytes());
        PluginManifest manifest = kernel.validator().validate(root, ManifestParser.schemaVersionOf(root));
        crossCheckDeclared(manifest, archive);
        return manifest;
    }

    /** 声明核对：声明资源必须存在；metadata/migrations 下未声明文件拒绝（docs/08 §4）。 */
    private static void crossCheckDeclared(PluginManifest manifest, SafeArchive archive) {
        Set<String> entries = archive.entryNames();
        Set<String> declared = Set.copyOf(allDeclared(manifest));
        for (String resource : allDeclared(manifest)) {
            if (!entries.contains(resource)) {
                throw PluginValidationException.invalidManifest("声明的资源在包内缺失: " + resource);
            }
        }
        for (String entry : entries) {
            if ((entry.startsWith("metadata/") || entry.startsWith("migrations/"))
                    && !declared.contains(entry)) {
                throw PluginValidationException.invalidManifest("包内文件未在 resources 声明: " + entry);
            }
        }
    }

    private static List<String> allDeclared(PluginManifest manifest) {
        List<String> all = new ArrayList<>();
        all.addAll(manifest.resources().entities());
        all.addAll(manifest.resources().views());
        all.addAll(manifest.resources().migrations());
        return all;
    }

    private void requireNoVersionConflict(PluginManifest manifest, String hash) {
        repository.findVersion(manifest.id(), manifest.version()).ifPresent(existing -> {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "版本 " + manifest.id() + "@" + manifest.version()
                            + " 已导入且内容不一致（checksum " + existing.contentHash()
                            + " ≠ " + hash + "，同版本不可变）");
        });
    }

    private PluginVersionRecord recordOf(PluginManifest manifest, String hash, int size,
                                         Map<String, String> checksums) {
        return new PluginVersionRecord("pv-" + UUID.randomUUID(), manifest.id(),
                manifest.version(), hash, manifest.capabilityLevel(), manifest.raw().toString(),
                checksums, size, null);
    }

    private InstallPreview transientPreview(PluginManifest manifest, String hash,
                                            Map<String, String> checksums) {
        return new InstallPreview(true, manifest.id(), manifest.name(), manifest.version(),
                manifest.capabilityLevel(), hash, null, checksums, manifest.dependencies());
    }

    private InstallPreview previewOf(PluginVersionRecord record, boolean isNew) {
        JsonNode manifest = JSON.readTree(record.manifestJson());
        JsonNode name = manifest.get("name");
        // 幂等命中路径不回查依赖表；依赖明细经插件清单接口获取（P08 inventory）
        return new InstallPreview(isNew, record.pluginId(),
                name == null ? record.pluginId() : name.asText(),
                record.version(), record.capabilityLevel(), record.contentHash(), record.id(),
                record.scriptChecksums(), List.of());
    }

    static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content)).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
