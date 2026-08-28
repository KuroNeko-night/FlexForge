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

    private final long maxCompressedBytes;
    private final long maxUncompressedBytes;
    private final int maxEntryCount;

    public ArchiveInspector() {
        this(10L * 1024 * 1024, 50L * 1024 * 1024, 2000);
    }

    /** 测试可注入小上限验证边界行为。 */
    ArchiveInspector(long maxCompressedBytes, long maxUncompressedBytes, int maxEntryCount) {
        this.maxCompressedBytes = maxCompressedBytes;
        this.maxUncompressedBytes = maxUncompressedBytes;
        this.maxEntryCount = maxEntryCount;
    }

    public SafeArchive inspect(byte[] zipBytes) throws IOException {
        requireZipShape(zipBytes);
        Path tempDir = Files.createTempDirectory("flexforge-plugin-");
        try {
            return extract(zipBytes, tempDir);
        } catch (IOException | RuntimeException e) {
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
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (extracted.size() >= maxEntryCount) {
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
        if (raw == null || raw.isEmpty() || raw.startsWith("/") || raw.contains("\\")
                || raw.chars().anyMatch(c -> c == 0)) {
            throw PluginValidationException.invalidManifest("非法包内路径: " + raw);
        }
        for (String segment : raw.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                throw PluginValidationException.invalidManifest("非法包内路径段: " + raw);
            }
            if (segment.equals("..")) {
                throw PluginValidationException.invalidManifest("zip-slip 路径拒绝（.. 段）: " + raw);
            }
        }
        return raw;
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

    /** 资产魔数嗅探（docs/13 §3.5-3：不信任类型声明；css/json/svg 拒绝二进制内容）。 */
    private static void sniffAsset(String entryName, Path file) throws IOException {
        String name = entryName.toLowerCase(Locale.ROOT);
        if (!name.startsWith("assets/") && !name.contains("/assets/")) {
            return;
        }
        byte[] head = readHead(file, entryName);
        if (!magicMatches(name, head)) {
            throw PluginValidationException.invalidManifest("资产魔数与声明类型不符: " + entryName);
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

    private static boolean magicMatches(String name, byte[] head) {
        return switch (extensionOf(name)) {
            case ".png" -> isPng(head);
            case ".webp" -> isWebp(head);
            case ".svg", ".css", ".json" -> !containsZero(head);
            default -> false;
        };
    }

    private static boolean isPng(byte[] head) {
        return head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
    }

    private static boolean isWebp(byte[] head) {
        return head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
    }

    private static boolean containsZero(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return true;
            }
        }
        return false;
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
