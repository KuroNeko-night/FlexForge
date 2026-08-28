package com.flexforge.plugin.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 解包结果（临时目录句柄）：close() 递归清理临时目录（docs/09 P07：校验/导入后无残留）。
 * 文件路径均为已通过安全校验的包内相对名。
 */
public final class SafeArchive implements AutoCloseable {

    private final Path tempDir;
    private final Path manifest;
    private final Map<String, Path> files;

    SafeArchive(Path tempDir, Path manifest, Map<String, Path> files) {
        this.tempDir = tempDir;
        this.manifest = manifest;
        this.files = files;
    }

    public byte[] manifestBytes() {
        return read(manifest);
    }

    public byte[] fileBytes(String name) {
        Path path = files.get(name);
        if (path == null) {
            throw new IllegalArgumentException("包内未找到已声明资源: " + name);
        }
        return read(path);
    }

    public Set<String> entryNames() {
        return files.keySet();
    }

    /** 临时目录（同包测试断言清理行为用）。 */
    Path tempDir() {
        return tempDir;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("读取包内文件失败: " + path, e);
        }
    }

    @Override
    public void close() {
        ArchiveInspector.deleteRecursively(tempDir);
    }
}
