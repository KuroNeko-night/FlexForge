package com.flexforge.plugin.application;

import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.ProcessorArtifactRepository.ArtifactRecord;
import com.flexforge.plugin.domain.ProcessorExecutionException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.regex.Pattern;

/**
 * 文件输入处理器编排（P23，FR-PLUGIN-14）：上传三重校验（扩展名 ∈ 声明
 * accept、魔数嗅探、大小 ≤ min(声明,5MB)，docs/13 §3.5-5）→ ProcessorRunner
 * 受控执行（argv/env 传平台生成路径）→ stdout 契约校验（file 输出：filename
 * 白名单字符集 + 产物在输出目录内且 ≤10MB）→ 产物登记（归属+TTL）→ 审计。
 * 产物下载的归属/过期校验亦在此（openForDownload）。
 */
@Service
public class FileProcessorService {

    /** 产物契约上限（docs/13 §3.5-5 唯一来源）。 */
    static final int MAX_ARTIFACT_BYTES = 10 * 1024 * 1024;
    static final int MAX_FILENAME_CHARS = 200;
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ActiveProcessorLocator locator;
    private final ProcessorRunner runner;
    private final ProcessorArtifactStore artifacts;
    private final AuditEventPort audit;
    private final Clock clock;

    public FileProcessorService(ActiveProcessorLocator locator, ProcessorRunner runner,
                                ProcessorArtifactStore artifacts, AuditEventPort audit,
                                Clock clock) {
        this.locator = locator;
        this.runner = runner;
        this.artifacts = artifacts;
        this.audit = audit;
        this.clock = clock;
    }

    /** 文件执行：成败均审计（与实体 invoke 同口径）。 */
    public JsonNode invokeFile(String actor, String key, String uploadName, byte[] payload) {
        try {
            JsonNode result = doInvokeFile(actor, key, uploadName, payload);
            audit.record(AuditEvents.of(actor, "plugin.processor.invoke-file", key,
                    "success", clock));
            return result;
        } catch (RuntimeException e) {
            audit.record(AuditEvents.of(actor, "plugin.processor.invoke-file", key,
                    "failure", clock));
            throw e;
        }
    }

    private JsonNode doInvokeFile(String actor, String key, String uploadName, byte[] payload) {
        ActiveProcessorLocator.ActiveProcessor processor = locator.findByKey(key);
        if (!processor.spec().fileMode()) {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "处理器 " + key + " 是实体输入模式，不接受文件上传");
        }
        validateUpload(processor.spec(), uploadName, payload);

        String artifactId = ProcessorArtifactStore.newArtifactId();
        Path targetDir = artifacts.newArtifactDir(artifactId);
        ProcessorRunner.FileRun run = runner.runFile(processor.script(), payload, targetDir);
        // runFile 之后产物目录已落盘但尚无登记行（sweep 只扫有行的过期目录）——
        // 校验/登记任一失败必须显式清理，否则目录泄漏到 TTL 之外（审查 P2-1）
        try {
            JsonNode output = parseStdout(run.stdout());
            ProcessorService.OutputValidator.validate(output, true);
            if ("file".equals(output.path("kind").asString())) {
                return fileResult(processor, output, artifactId, targetDir, actor);
            }
            // 非文件输出（table/summary/chart 分析形态）：产物目录未使用即清理
            artifacts.discard(targetDir);
            return output;
        } catch (RuntimeException e) {
            artifacts.discard(targetDir);
            throw e;
        }
    }

    /** 上传三重校验（S1：不信任客户端文件名与类型声明）。 */
    private static void validateUpload(com.flexforge.common.contract.ProcessorContribution spec,
                                       String uploadName, byte[] payload) {
        String ext = extensionOf(uploadName);
        if (!spec.accept().contains(ext)) {
            throw ProcessorExecutionException.inputInvalid(
                    "文件扩展名 ." + ext + " 不在处理器声明白名单 " + spec.accept());
        }
        long limit = Math.min(spec.maxInputMB() == null ? 1 : spec.maxInputMB(),
                com.flexforge.plugin.domain.ProcessorSpec.MAX_INPUT_MB) * 1024L * 1024L;
        if (payload.length == 0 || payload.length > limit) {
            throw ProcessorExecutionException.inputInvalid(
                    "文件大小须在 1 字节.." + limit + " 字节之间（当前 " + payload.length + "）");
        }
        if (!sniffOk(ext, payload)) {
            throw ProcessorExecutionException.inputInvalid(
                    "文件内容与扩展名 ." + ext + " 不符（魔数嗅探失败，可能为伪装文件）");
        }
    }

    private static String extensionOf(String uploadName) {
        if (uploadName == null) {
            return "";
        }
        int dot = uploadName.lastIndexOf('.');
        return dot < 0 ? "" : uploadName.substring(dot + 1).toLowerCase();
    }

    /** 魔数嗅探：xlsx=PK zip 头；csv/txt=前 4KB 无 NUL（二进制拒绝）。 */
    private static boolean sniffOk(String ext, byte[] payload) {
        if ("xlsx".equals(ext)) {
            return payload.length > 4 && payload[0] == 'P' && payload[1] == 'K';
        }
        int probe = Math.min(payload.length, 4096);
        for (int i = 0; i < probe; i++) {
            if (payload[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private JsonNode fileResult(ActiveProcessorLocator.ActiveProcessor processor, JsonNode output,
                                String artifactId, Path targetDir, String actor) {
        String filename = output.path("filename").asString("");
        if (!SAFE_FILENAME.matcher(filename).matches() || filename.contains("..")
                || filename.length() > MAX_FILENAME_CHARS) {
            throw ProcessorExecutionException.outputInvalid(
                    "file.filename 须为安全字符集（字母数字._-，≤" + MAX_FILENAME_CHARS + " 字符）");
        }
        Path produced = targetDir.resolve(filename).normalize();
        if (!produced.startsWith(targetDir) || !Files.isRegularFile(produced)) {
            throw ProcessorExecutionException.outputInvalid(
                    "产物文件未在输出目录内: " + filename);
        }
        long size = sizeOf(produced);
        if (size > MAX_ARTIFACT_BYTES) {
            throw ProcessorExecutionException.outputInvalid(
                    "产物超过 " + MAX_ARTIFACT_BYTES + " 字节上限");
        }
        ArtifactRecord record = artifacts.register(artifactId,
                new ProcessorArtifactStore.Registration(processor.spec().key(), filename,
                        ProcessorArtifactStore.contentTypeOf(filename), size, targetDir),
                actor);
        ObjectNode result = JSON.createObjectNode();
        result.put("kind", "file");
        result.put("artifactId", record.id());
        result.put("filename", filename);
        result.put("sizeBytes", size);
        result.put("contentType", record.contentType());
        result.put("expiresAt", record.expiresAt().toString());
        return result;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw ProcessorExecutionException.outputInvalid("产物文件读取失败");
        }
    }

    private static JsonNode parseStdout(String stdout) {
        try {
            return JSON.readTree(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw ProcessorExecutionException.outputInvalid("处理器输出不是合法 JSON");
        }
    }

    /** 产物下载校验（S2：归属本人或 ADMIN；过期 410；读时兜底防清理滞后）。 */
    public Download openForDownload(String actor, boolean admin, String artifactId) {
        ArtifactRecord record = artifacts.find(artifactId);
        if (record == null || (!admin && !record.createdBy().equals(actor))) {
            throw ProcessorExecutionException.artifactNotFound("产物不存在或无权访问");
        }
        if (record.expiresAt().isBefore(clock.instant())) {
            throw ProcessorExecutionException.artifactExpired("产物已过期（TTL "
                    + ProcessorArtifactStore.TTL.toMinutes() + " 分钟）");
        }
        Path file = artifacts.fileOf(record);
        if (!Files.isRegularFile(file)) {
            throw ProcessorExecutionException.artifactNotFound("产物文件已不存在");
        }
        artifacts.markDownloaded(artifactId);
        audit.record(AuditEvents.of(actor, "plugin.processor.artifact.download", artifactId,
                record.filename(), clock));
        return new Download(file, record.filename(), record.contentType());
    }

    /** 下载句柄（文件路径 + 展示名 + Content-Type）。 */
    public record Download(Path file, String filename, String contentType) {
    }
}
