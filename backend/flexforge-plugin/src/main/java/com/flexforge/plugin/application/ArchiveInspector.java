package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.PluginValidationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 插件包解包与上传安全基线（docs/09 P07 / docs/13 §3.5 / S6）：
 * zip 魔数嗅探、压缩/解压大小上限、条目数上限、zip-slip 与非法路径拒绝、
 * 区域-扩展名白名单、资产魔数嗅探、解压到临时目录并在关闭时清理。
 * 声明核对（资源是否在 manifest 中声明）由导入服务在解析 manifest 后执行。
 */
@Component
public class ArchiveInspector {

    /** 区域扩展名白名单（S6：无任意可执行资源）。 */
    private static final Map<String, Set<String>> AREA_EXTENSIONS = Map.of(
            "metadata", Set.of(".json"),
            "migrations", Set.of(".sql"),
            "assets", Set.of(".png", ".svg", ".webp", ".css", ".json"));

    /** 文本类资产扩展名（全文内容嗅探，拒绝内嵌脚本）。 */
    private static final Set<String> TEXT_ASSET_EXTENSIONS = Set.of(".svg", ".css", ".json");

    private final long maxCompressedBytes;
    private final long maxUncompressedBytes;
    private final int maxEntryCount;

    public ArchiveInspector() {
        // docs/13 §3.5-2（数值唯一来源）：条目 ≤1000、解压 ≤50MB；压缩 ≤10MB 见 §3.5-1
        this(10L * 1024 * 1024, 50L * 1024 * 1024, 1000);
    }

    /** 测试可注入小上限验证边界行为。 */
    ArchiveInspector(long maxCompressedBytes, long maxUncompressedBytes, int maxEntryCount) {
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.maxEntryCount = maxEntryCount;
    }

    /** 生产默认条目上限（docs/13 §3.5-2 = 1000；测试断言用）。 */
    int maxEntryCount() {
        return maxEntryCount;
    }

    public SafeArchive inspect(byte[] zipBytes) throws IOException {
        requireZipShape(zipBytes);
        Path tempDir = Files.createTempDirectory("flexforge-plugin-");
        try {
            return extract(zipBytes, tempDir);
        } catch (IOException e) {
            deleteRecursively(tempDir);
            // 损坏/截断 zip、非法文件名字符等：统一 400 invalid_manifest（docs/08 §7）
            throw PluginValidationException.invalidManifest("插件包损坏或不可读（"
                    + e.getClass().getSimpleName() + "）");
        } catch (RuntimeException e) {
            deleteRecursively(tempDir);
            throw e;
        }
    }

    private void requireZipShape(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw PluginValidationException.invalidManifest("插件包为空");
        }
        if (zipBytes.length > maxCompressedBytes) {
            throw new PluginValidationException(com.flexforge.common.api.ErrorCodes.VALIDATION_ERROR,
                    "插件包超过压缩大小上限 " + maxCompressedBytes + " 字节");
        }
        boolean zipMagic = zipBytes[0] == 'P' && zipBytes[1] == 'K'
                && (zipBytes[2] == 3 || zipBytes[2] == 5 || zipBytes[2] == 7);
        if (!zipMagic) {
            throw PluginValidationException.invalidManifest("文件魔数不是 zip（PK），拒绝处理");
        }
    }

    private SafeArchive extract(byte[] zipBytes, Path tempDir) throws IOException {
        Path zipFile = tempDir.resolve("package.zip");
        Files.write(zipFile, zipBytes);
        Map<String, Path> extracted = new LinkedHashMap<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            var entries = zip.entries();
            int seen = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // 目录条目同样计入（docs/13 §3.5-2 文件数量含目录风暴面）
                if (++seen > maxEntryCount) {
                    throw PluginValidationException.invalidManifest("插件包条目数超过上限 " + maxEntryCount);
                }
                String name = safeName(entry.getName());
                Path target = tempDir.resolve(name).normalize();
                if (!target.startsWith(tempDir)) {
                    throw PluginValidationException.invalidManifest("zip-slip 路径拒绝: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                requireAreaAllowed(name);
                total += copyEntry(zip, entry, name, target, total);
                extracted.put(name, target);
            }
        }
        Path manifest = extracted.get("plugin.json");
        if (manifest == null) {
            throw PluginValidationException.invalidManifest("缺少 plugin.json");
        }
        return new SafeArchive(tempDir, manifest, extracted);
    }

    private long copyEntry(ZipFile zip, ZipEntry entry, String entryName, Path target, long totalSoFar)
            throws IOException {
        Files.createDirectories(target.getParent());
        long copied = 0;
        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                copied += read;
                if (totalSoFar + copied > maxUncompressedBytes) {
                    throw new PluginValidationException(
                            com.flexforge.common.api.ErrorCodes.VALIDATION_ERROR,
                            "插件包解压后超过大小上限 " + maxUncompressedBytes + " 字节（疑似 zip 炸弹）");
                }
                out.write(buffer, 0, read);
            }
        }
        sniffAsset(entryName, target);
        return copied;
    }

    private static String safeName(String raw) {
        // docs/13 §3.5-2：拒绝控制字符与超长路径（防日志注入与 OS 层异常路径）
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.contains("\\")
                || raw.length() > 400 || raw.chars().anyMatch(Character::isISOControl)) {
            throw PluginValidationException.invalidManifest("非法包内路径: " + raw);
        }
        for (String segment : raw.split("/")) {
            requirePlainSegment(segment, raw);
        }
        return raw;
    }

    private static void requirePlainSegment(String segment, String raw) {
        if (segment.isEmpty() || segment.equals(".")) {
            throw PluginValidationException.invalidManifest("非法包内路径段: " + raw);
        }
        if (segment.length() > 200) {
            throw PluginValidationException.invalidManifest("包内路径段超长（>200）: " + raw);
        }
        if (segment.equals("..")) {
            throw PluginValidationException.invalidManifest("zip-slip 路径拒绝（.. 段）: " + raw);
        }
    }

    private static void requireAreaAllowed(String name) {
        if (!name.equals("plugin.json")) {
            requireKnownArea(name);
            requireAllowedExtension(name);
        }
    }

    private static void requireKnownArea(String name) {
        String area = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
        if (!AREA_EXTENSIONS.containsKey(area)) {
            throw PluginValidationException.invalidManifest("未声明的包内区域拒绝: " + name);
        }
    }

    private static void requireAllowedExtension(String name) {
        String area = name.substring(0, name.indexOf('/'));
        String lower = name.toLowerCase(Locale.ROOT);
        boolean allowed = AREA_EXTENSIONS.get(area).stream().anyMatch(lower::endsWith);
        if (!allowed) {
            throw PluginValidationException.invalidManifest(
                    "资源类型不在白名单（区域 " + area + "）: " + name);
        }
    }

    /** 资产魔数嗅探（docs/13 §3.5-3：不信任类型声明；svg/css/json 拒二进制与脚本内容）。 */
    private static void sniffAsset(String entryName, Path file) throws IOException {
        String name = entryName.toLowerCase(Locale.ROOT);
        if (!name.startsWith("assets/") && !name.contains("/assets/")) {
            return;
        }
        String extension = extensionOf(name);
        if (TEXT_ASSET_EXTENSIONS.contains(extension)) {
            requireNoScriptContent(entryName, file);
            return;
        }
        byte[] head = readHead(file, entryName);
        boolean ok = switch (extension) {
            case ".png" -> isPng(head);
            case ".webp" -> isWebp(head);
            default -> false;
        };
        if (!ok) {
            throw PluginValidationException.invalidManifest("资产魔数与声明类型不符: " + entryName);
        }
    }

    /** 文本类资产（svg/css/json）：全文拒绝内嵌脚本（S6：无任意可执行资源）。 */
    private static void requireNoScriptContent(String entryName, Path file) throws IOException {
        String content = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (content.contains("<script") || content.contains("javascript:")) {
            throw PluginValidationException.invalidManifest(
                    "文本资产含可执行脚本内容，拒绝（S6）: " + entryName);
        }
    }

    private static byte[] readHead(Path file, String name) throws IOException {
        byte[] head = new byte[16];
        int len = Math.min(16, (int) Files.size(file));
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, len);
            if (read < len) {
                throw PluginValidationException.invalidManifest("资产读取失败: " + name);
            }
        }
        return head;
    }

    private static boolean isPng(byte[] head) {
        return head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
    }

    private static boolean isWebp(byte[] head) {
        // RIFF 容器需同时校验 8..11 字节的 WEBP 标识（区分 wav/avi）
        return head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F'
                && head[3] == 'F' && head[8] == 'W' && head[9] == 'E' && head[10] == 'B'
                && head[11] == 'P';
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不阻断主流程；临时目录由操作系统兜底
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
