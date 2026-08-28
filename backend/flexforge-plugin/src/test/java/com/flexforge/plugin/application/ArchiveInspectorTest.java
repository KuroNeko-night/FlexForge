package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.PluginValidationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 上传安全基线（RB-PLUGIN-VALID：zip-slip/大小上限/白名单/魔数/临时目录清理）。 */
class ArchiveInspectorTest {


    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    static byte[] zip(Map<String, byte[]> entries) {
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

    static Map<String, byte[]> validPackage() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("plugin.json", "{\"schemaVersion\":1}".getBytes());
        entries.put("migrations/V001__demo.sql", "CREATE TABLE inv_item (id INT);".getBytes());
        entries.put("assets/logo.png", PNG_MAGIC);
        return entries;
    }

    private static PluginValidationException reject(byte[] zipBytes) {
        try (SafeArchive ignored = new ArchiveInspector().inspect(zipBytes)) {
            throw new AssertionError("应当被拒绝");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void validArchiveExtractsAndCleansUpTempDir() throws IOException {
        byte[] bytes = zip(validPackage());
        try (SafeArchive archive = new ArchiveInspector().inspect(bytes)) {
            assertThat(archive.entryNames()).containsExactlyInAnyOrder(
                    "plugin.json", "migrations/V001__demo.sql", "assets/logo.png");
            assertThat(new String(archive.manifestBytes())).contains("schemaVersion");
        }
    }

    @Test
    void nonZipMagicRejected() {
        assertThatThrownBy(() -> reject("not a zip".getBytes()))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("魔数");
    }

    @Test
    void zipSlipRejected() {
        var entries = new LinkedHashMap<>(validPackage());
        entries.put("../evil.txt", "x".getBytes());
        assertThatThrownBy(() -> reject(zip(entries)))
                .hasMessageContaining("zip-slip");
    }

    @Test
    void absolutePathAndOddSegmentsRejected() {
        var absolute = new LinkedHashMap<>(validPackage());
        absolute.put("/etc/passwd", "x".getBytes());
        assertThatThrownBy(() -> reject(zip(absolute))).hasMessageContaining("非法包内路径");

        var dot = new LinkedHashMap<>(validPackage());
        dot.put("metadata/../secret.json", "x".getBytes());
        assertThatThrownBy(() -> reject(zip(dot))).hasMessageContaining("zip-slip");
    }

    @Test
    void unknownAreaAndExtensionRejected() {
        var area = new LinkedHashMap<>(validPackage());
        area.put("readme.md", "x".getBytes());
        assertThatThrownBy(() -> reject(zip(area))).hasMessageContaining("未声明的包内区域");

        var extension = new LinkedHashMap<>(validPackage());
        extension.put("metadata/entity.txt", "x".getBytes());
        assertThatThrownBy(() -> reject(zip(extension))).hasMessageContaining("资源类型不在白名单");

        var script = new LinkedHashMap<>(validPackage());
        script.put("assets/evil.js", "alert(1)".getBytes());
        assertThatThrownBy(() -> reject(zip(script))).hasMessageContaining("资源类型不在白名单");
    }

    @Test
    void assetMagicMismatchRejected() {
        var entries = new LinkedHashMap<>(validPackage());
        entries.put("assets/fake.png", "actually text".getBytes());
        assertThatThrownBy(() -> reject(zip(entries))).hasMessageContaining("魔数与声明类型不符");
    }

    @Test
    void missingManifestRejected() {
        var entries = new LinkedHashMap<>(validPackage());
        entries.remove("plugin.json");
        assertThatThrownBy(() -> reject(zip(entries))).hasMessageContaining("plugin.json");
    }

    @Test
    void sizeAndCountCapsEnforced() {
        // 解压后上限（压缩层可容纳：200 字节零内容 deflate 后很小）
        ArchiveInspector bombInspector = new ArchiveInspector(10_000, 100, 100);
        var bomb = new LinkedHashMap<String, byte[]>();
        bomb.put("plugin.json", "{\"schemaVersion\":1}".getBytes());
        bomb.put("assets/big.png", new byte[200]);
        assertThatThrownBy(() -> bombInspector.inspect(zip(bomb)))
                .hasMessageContaining("解压后超过大小上限");

        // 条目数上限
        ArchiveInspector countInspector = new ArchiveInspector(10_000, 10_000, 3);
        var tooMany = new LinkedHashMap<String, byte[]>();
        tooMany.put("plugin.json", "{}".getBytes());
        for (int i = 0; i < 5; i++) {
            tooMany.put("assets/a" + i + ".png", PNG_MAGIC);
        }
        assertThatThrownBy(() -> countInspector.inspect(zip(tooMany))).hasMessageContaining("条目数超过上限");

        // 压缩上限（zip 流本身超限）
        ArchiveInspector tiny = new ArchiveInspector(100, 10_000, 100);
        var over = new LinkedHashMap<String, byte[]>();
        over.put("plugin.json", new byte[200]);
        assertThatThrownBy(() -> tiny.inspect(zip(over))).hasMessageContaining("压缩大小上限");
    }

    @Test
    void defaultEntryCapMatchesSecurityBaseline() {
        // docs/13 §3.5-2：解压后文件数量 ≤ 1000（数值唯一来源）
        assertThat(new ArchiveInspector().maxEntryCount()).isEqualTo(1000);
    }

    @Test
    void corruptedZipWithValidMagicRejectedAsInvalidManifest() {
        byte[] corrupted = new byte[] {'P', 'K', 3, 4, 'g', 'a', 'r', 'b', 'a', 'g', 'e'};
        assertThatThrownBy(() -> reject(corrupted))
                .isInstanceOf(PluginValidationException.class)
                .hasMessageContaining("损坏或不可读");
    }

    @Test
    void controlCharactersAndOverlongSegmentsRejected() {
        var control = new LinkedHashMap<>(validPackage());
        control.put("assets/ev\til.png", PNG_MAGIC);
        assertThatThrownBy(() -> reject(zip(control))).hasMessageContaining("非法包内路径");

        var longSegment = new LinkedHashMap<>(validPackage());
        longSegment.put("assets/" + "x".repeat(201) + ".png", PNG_MAGIC);
        assertThatThrownBy(() -> reject(zip(longSegment))).hasMessageContaining("超长");
    }

    @Test
    void svgWithEmbeddedScriptRejectedWhileCleanTextPasses() {
        var evil = new LinkedHashMap<>(validPackage());
        evil.put("assets/icon.svg", "<svg xmlns=\"...\"><script>alert(1)</script></svg>".getBytes());
        assertThatThrownBy(() -> reject(zip(evil)))
                .hasMessageContaining("可执行脚本内容");

        var clean = new LinkedHashMap<>(validPackage());
        clean.put("assets/icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes());
        try (SafeArchive archive = new ArchiveInspector().inspect(zip(clean))) {
            assertThat(archive.entryNames()).contains("assets/icon.svg");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void tempDirRemovedAfterCloseAndAfterFailure() throws IOException {
        // 关闭即清理：直接检查本包的临时目录
        byte[] bytes = zip(validPackage());
        Path tempDir;
        try (SafeArchive archive = new ArchiveInspector().inspect(bytes)) {
            tempDir = archive.tempDir();
            assertThat(tempDir).exists();
        }
        assertThat(tempDir).doesNotExist();

        // 失败路径同样清理：以非递归快照对比 tmp 根下的 flexforge-plugin-* 目录
        var before = pluginTempDirs();
        var evil = new LinkedHashMap<>(validPackage());
        evil.put("../escape.txt", "x".getBytes());
        assertThatThrownBy(() -> new ArchiveInspector().inspect(zip(evil)))
                .hasMessageContaining("zip-slip");
        assertThat(pluginTempDirs()).containsExactlyInAnyOrderElementsOf(before);
    }

    private static java.util.Set<String> pluginTempDirs() throws IOException {
        java.util.Set<String> names = new java.util.TreeSet<>();
        try (var stream = Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),
                "flexforge-plugin-*")) {
            stream.forEach(dir -> names.add(dir.getFileName().toString()));
        }
        return names;
    }
}
