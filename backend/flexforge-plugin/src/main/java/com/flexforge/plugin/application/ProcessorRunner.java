package com.flexforge.plugin.application;

import com.flexforge.plugin.domain.ProcessorExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 处理器执行引擎（ADR-0002 Level 2 受信边界，docs/09 P20）：脚本字节落临时目录，
 * 子进程 {@code python3 -I}（隔离模式）执行；环境清空至最小集（无任何平台凭据键，
 * S6 修订）；stdin 经文件重定向（零管道死锁）；硬超时 kill；stdout 字节上限。
 * 执行结束临时目录必清理。
 *
 * <p>命令构造安全口径（S6 修订/ADR-0002）：进程命令是编译期确定的固定形态
 * {@code <解释器> -I processor.py}——解释器只有 "python3"/"python" 两个内置字面量
 * 候选（容器/CI 为 python3，Windows 开发机常只有 python；不支持自定义路径，
 * 简化即收敛攻击面），进程参数永不经 shell、插件声明的 entry 路径不进入命令行
 * （脚本字节由平台解包落临时目录后以固定名执行）。
 */
@Component
public class ProcessorRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessorRunner.class);

    /** 硬超时与 stdout 上限（docs/09 P20 红线数值唯一来源）。 */
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_STDOUT_BYTES = 1024 * 1024;
    private static final int MAX_STDERR_LOG_BYTES = 400;

    /** 解释器候选（内置字面量；探测结果缓存）。 */
    private enum PythonBin { PYTHON3, PYTHON }

    private volatile PythonBin resolvedBin;

    /** 执行脚本：stdin=inputJson（文件重定向），返回 stdout 全文；失败抛 ProcessorExecutionException。
     * stdout 在后台线程采集（进程不产出时 read 阻塞，不占住 waitFor 的超时判定）。 */
    public String run(byte[] scriptBytes, String inputJson) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("flexforge-processor-");
            Files.write(workDir.resolve("processor.py"), scriptBytes);
            Files.write(workDir.resolve("input.json"), inputJson.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder builder = pythonProcess(resolveBin());
            builder.directory(workDir.toFile());
            builder.redirectInput(workDir.resolve("input.json").toFile());
            builder.redirectErrorStream(false);
            sanitizeEnvironment(builder);

            Process process = builder.start();
            java.util.concurrent.atomic.AtomicReference<String> stdoutRef = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<RuntimeException> readError = new java.util.concurrent.atomic.AtomicReference<>();
            startCollector(process, stdoutRef, readError);

            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw ProcessorExecutionException.failed("处理器执行超时（上限 "
                        + TIMEOUT.toSeconds() + " 秒）");
            }
            if (readError.get() != null) {
                throw readError.get();
            }
            requireZeroExit(process);
            return stdoutRef.get() == null ? "" : stdoutRef.get();
        } catch (IOException e) {
            throw ProcessorExecutionException.failed("处理器进程启动失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ProcessorExecutionException.failed("处理器执行被中断");
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /** stdout 后台采集线程（read 阻塞不影响主线程超时判定）。 */
    private static void startCollector(Process process,
                                       java.util.concurrent.atomic.AtomicReference<String> stdoutRef,
                                       java.util.concurrent.atomic.AtomicReference<RuntimeException> readError) {
        Thread collector = new Thread(() -> {
            try {
                stdoutRef.set(readBounded(process.getInputStream()));
            } catch (IOException | RuntimeException e) {
                readError.set(ProcessorExecutionException.failed("处理器输出读取失败"));
            }
        }, "processor-stdout");
        collector.setDaemon(true);
        collector.start();
    }

    /** 非零退出统一 processor_failed（stderr 摘要只进服务端日志，S8）。 */
    private static void requireZeroExit(Process process) {
        byte[] stderr;
        try {
            stderr = readAllBounded(process.getErrorStream());
        } catch (IOException e) {
            stderr = new byte[0];
        }
        int exit = process.exitValue();
        if (exit != 0) {
            log.warn("处理器非零退出 code={} stderr={}", exit, abbreviate(stderr));
            throw ProcessorExecutionException.failed("处理器执行失败（退出码 " + exit + "）");
        }
    }

    /** 进程构造单点：固定命令形态，字面量参数，无任何动态字符串。
     * -X utf8：stdio 编码跨平台钉死 UTF-8（-I 隔离模式会忽略 PYTHONUTF8 环境变量，
     * Windows 默认 GBK 将破坏 stdin/stdout JSON 契约，故走命令行标志）。 */
    private static ProcessBuilder pythonProcess(PythonBin bin) {
        return switch (bin) {
            case PYTHON3 -> new ProcessBuilder("python3", "-I", "-X", "utf8", "processor.py");
            case PYTHON -> new ProcessBuilder("python", "-I", "-X", "utf8", "processor.py");
        };
    }

    /** 环境清空至最小集：仅保留解释器解析所必需的 PATH 与 Windows 系统键；
     * stdio 的 UTF-8 由命令行 -X utf8 钉死（-I 会忽略 PYTHON* 环境变量）；
     * 平台凭据类键（DB_/AUTH_/FLEXFORGE_）无从进入（S6 修订）。 */
    private static void sanitizeEnvironment(ProcessBuilder builder) {
        Map<String, String> env = builder.environment();
        Map<String, String> keep = Map.of(
                "PATH", env.getOrDefault("PATH", System.getenv("PATH")),
                "PATHEXT", env.getOrDefault("PATHEXT", System.getenv("PATHEXT")),
                "SystemRoot", env.getOrDefault("SystemRoot", System.getenv("SystemRoot")));
        env.clear();
        env.putAll(keep);
    }

    private PythonBin resolveBin() {
        PythonBin cached = resolvedBin;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (resolvedBin == null) {
                resolvedBin = firstUsable();
            }
            return resolvedBin;
        }
    }

    /** 探测顺序：python3（容器/CI）→ python（Windows 开发机）。 */
    private PythonBin firstUsable() {
        if (probe(PythonBin.PYTHON3)) {
            return PythonBin.PYTHON3;
        }
        if (probe(PythonBin.PYTHON)) {
            return PythonBin.PYTHON;
        }
        throw ProcessorExecutionException.failed(
                "未找到可用 Python 解释器（python3/python），运行镜像需包含 python3");
    }

    private static boolean probe(PythonBin bin) {
        ProcessBuilder probeBuilder = switch (bin) {
            case PYTHON3 -> new ProcessBuilder("python3", "--version");
            case PYTHON -> new ProcessBuilder("python", "--version");
        };
        try {
            Process probeProcess = probeBuilder.start();
            if (!probeProcess.waitFor(5, TimeUnit.SECONDS)) {
                probeProcess.destroyForcibly();
                return false;
            }
            return probeProcess.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** 读 stdout 并施加字节上限（超限即失败，防异常脚本刷爆内存）。 */
    private static String readBounded(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            if (buffer.size() + read > MAX_STDOUT_BYTES) {
                throw ProcessorExecutionException.failed(
                        "处理器输出超过上限 " + MAX_STDOUT_BYTES + " 字节");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readAllBounded(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            if (buffer.size() + read > MAX_STDERR_LOG_BYTES) {
                break;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String abbreviate(byte[] stderr) {
        String text = new String(stderr, StandardCharsets.UTF_8).replace("\n", " ").trim();
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 临时目录清理尽力而为（OS 重启自清）
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
