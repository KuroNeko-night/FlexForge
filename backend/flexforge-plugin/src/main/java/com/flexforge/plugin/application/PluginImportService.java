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
            PluginVersionRecord hit = existing.orElseThrow();
            repository.resetUninstalledInstance(hit.pluginId()); // 幂等命中也复活 uninstalled 实例（与 storeVersion CASE 同口径）
            return previewOf(hit, false);
        }
        try (SafeArchive archive = kernel.inspector().inspect(zipBytes)) {
            PluginManifest manifest = inspectManifest(archive);
            Map<String, String> checksums = kernel.scanner().scan(
                    manifest.resources().migrations(), archive::fileBytes);
            kernel.resolver().resolve(manifest.dependencies(), repository::versionsOf);
            requireNoVersionConflict(manifest, hash);
            PluginVersionRecord candidate = recordOf(manifest, hash, zipBytes.length,
                    checksums, archive);
            PluginVersionRecord stored = storeIdempotently(manifest, candidate, hash);
            audit.record(AuditEvents.of(actor, "plugin.import", stored.id(), "success", clock));
            return previewOf(stored, stored.id().equals(candidate.id()));
        }
    }

    /** 并发窗口兜底：唯一约束冲突后复查——hash 命中即幂等返回；否则按同版本冲突转 400。 */
    private PluginVersionRecord storeIdempotently(PluginManifest manifest,
                                                  PluginVersionRecord candidate, String hash) {
        try {
            return repository.storeVersion(manifest.name(), candidate, manifest.dependencies());
        } catch (DuplicateKeyException e) {
            var winner = repository.findByContentHash(hash);
            if (winner.isPresent()) {
                return winner.orElseThrow();
            }
            // 并发同版本异内容：败者事务已回滚，按串行路径同口径拒绝
            requireNoVersionConflict(manifest, hash);
            throw e;
        }
    }

    private PluginManifest inspectManifest(SafeArchive archive) {
        JsonNode root = ManifestParser.parse(archive.manifestBytes());
        PluginManifest manifest = kernel.validator().validate(root, ManifestParser.schemaVersionOf(root));
        crossCheckDeclared(manifest, archive);
        return manifest;
    }

    /**
     * 声明核对：声明资源必须存在；metadata/migrations 下未声明文件拒绝（docs/08 §4）；
     * assets/ 逐文件声明收紧（Issue #20 第 3 项）——每个资产必须被某条 themeAssets
     * 贡献引用，themeAssets 引用的资产必须存在于包内。
     */
    private static void crossCheckDeclared(PluginManifest manifest, SafeArchive archive) {
        Set<String> entries = archive.entryNames();
        Set<String> declared = Set.copyOf(allDeclared(manifest));
        for (String resource : allDeclared(manifest)) {
            if (!entries.contains(resource)) {
                throw PluginValidationException.invalidManifest("声明的资源在包内缺失: " + resource);
            }
        }
        for (String entry : entries) {
            if (isUndeclaredManagedFile(entry, declared)) {
                throw PluginValidationException.invalidManifest("包内文件未在 resources 声明: " + entry);
            }
        }
        requireAssetsDeclared(manifest, entries);
        requireScriptsDeclared(manifest, entries);
    }

    /** scripts/ 双向核对（P20，S6 修订）：Level 2 时声明 entry 必在包内、包内
     * scripts/*.py 必被某条 processors 声明引用；Level 1 时 scripts/ 出现任何文件即拒。 */
    private static void requireScriptsDeclared(PluginManifest manifest, Set<String> entries) {
        if (manifest.capabilityLevel() != 2) {
            for (String entry : entries) {
                if (entry.startsWith("scripts/")) {
                    throw PluginValidationException.invalidManifest(
                            "Level 1 插件不允许携带脚本文件（纯声明式，S6）: " + entry);
                }
            }
            return;
        }
        Set<String> declaredScripts = new java.util.HashSet<>();
        for (com.flexforge.plugin.domain.ProcessorSpec spec : manifest.processors()) {
            declaredScripts.add(spec.entry());
        }
        for (String script : declaredScripts) {
            if (!entries.contains(script)) {
                throw PluginValidationException.invalidManifest(
                        "processors 声明的脚本在包内缺失: " + script);
            }
        }
        for (String entry : entries) {
            if (entry.startsWith("scripts/") && !declaredScripts.contains(entry)) {
                throw PluginValidationException.invalidManifest(
                        "包内脚本文件未被 contributions.processors 声明: " + entry);
            }
        }
    }

    private static boolean isUndeclaredManagedFile(String entry, Set<String> declared) {
        boolean managed = entry.startsWith("metadata/") || entry.startsWith("migrations/");
        return managed && !declared.contains(entry);
    }

    /** assets/ 双向核对：声明必在包内、包内必有声明（Issue #20 第 3 项收紧）。 */
    private static void requireAssetsDeclared(PluginManifest manifest, Set<String> entries) {
        Set<String> declaredAssets = new java.util.HashSet<>();
        for (com.flexforge.plugin.domain.ThemeAssetSpec asset : manifest.themeAssets()) {
            declaredAssets.add(asset.path());
        }
        for (String assetPath : declaredAssets) {
            if (!entries.contains(assetPath)) {
                throw PluginValidationException.invalidManifest(
                        "themeAssets 声明的资产在包内缺失: " + assetPath);
            }
        }
        for (String entry : entries) {
            if (entry.startsWith("assets/") && !declaredAssets.contains(entry)) {
                throw PluginValidationException.invalidManifest(
                        "包内资产文件未被 contributions.themeAssets 声明: " + entry);
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
                                         Map<String, String> checksums, SafeArchive archive) {
        Map<String, String> scriptPayloads = new java.util.LinkedHashMap<>();
        for (String script : manifest.resources().migrations()) {
            scriptPayloads.put(script,
                    new String(archive.fileBytes(script), java.nio.charset.StandardCharsets.UTF_8));
        }
        Map<String, String> resourcePayloads = new java.util.LinkedHashMap<>();
        for (String path : manifest.resources().entities()) {
            resourcePayloads.put(path,
                    new String(archive.fileBytes(path), java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String path : manifest.resources().views()) {
            resourcePayloads.put(path,
                    new String(archive.fileBytes(path), java.nio.charset.StandardCharsets.UTF_8));
        }
        Map<String, String> assetPayloads = new java.util.LinkedHashMap<>();
        for (com.flexforge.plugin.domain.ThemeAssetSpec asset : manifest.themeAssets()) {
            assetPayloads.putIfAbsent(asset.path(),
                    java.util.Base64.getEncoder().encodeToString(archive.fileBytes(asset.path())));
        }
        for (com.flexforge.plugin.domain.ProcessorSpec spec : manifest.processors()) {
            assetPayloads.putIfAbsent(spec.entry(),
                    java.util.Base64.getEncoder().encodeToString(archive.fileBytes(spec.entry())));
        }
        return new PluginVersionRecord("pv-" + UUID.randomUUID(), manifest.id(),
                manifest.version(), hash, manifest.capabilityLevel(), manifest.raw().toString(),
                checksums, size, null, scriptPayloads, resourcePayloads, assetPayloads);
    }

    private InstallPreview transientPreview(PluginManifest manifest, String hash,
                                            Map<String, String> checksums) {
        return new InstallPreview(true, manifest.id(), manifest.name(), manifest.version(),
                manifest.capabilityLevel(), hash, null, checksums, manifest.dependencies());
    }

    private InstallPreview previewOf(PluginVersionRecord record, boolean isNew) {
        JsonNode manifest = JSON.readTree(record.manifestJson());
        JsonNode name = manifest.get("name");
        // 幂等命中与 validate 同口径返回全量依赖（Issue #20 第 2 项统一）
        return new InstallPreview(isNew, record.pluginId(),
                name == null ? record.pluginId() : name.asText(),
                record.version(), record.capabilityLevel(), record.contentHash(), record.id(),
                record.scriptChecksums(), dependenciesOf(manifest));
    }

    private static List<com.flexforge.plugin.domain.DependencySpec> dependenciesOf(
            JsonNode manifest) {
        JsonNode node = manifest.path("dependencies");
        List<com.flexforge.plugin.domain.DependencySpec> result = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            result.add(new com.flexforge.plugin.domain.DependencySpec(
                    node.get(i).path("pluginId").asString(),
                    node.get(i).path("versionRange").asString("*")));
        }
        return List.copyOf(result);
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
