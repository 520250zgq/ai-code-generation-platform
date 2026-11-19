package com.zgq.zgqaicodemother.workflow.tools.mermaid;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 mermaid-cli（mmdc）的渲染器。
 *
 * <p>命令通过 {@link ProcessBuilder} 的参数列表执行，不拼接 shell 字符串，模型输出无法注入
 * 操作系统命令。渲染在独立临时目录中进行，超时后强制结束进程及其子进程，并校验产物存在、
 * 大小和文件头。</p>
 *
 * <p>生产部署需要先安装 Node.js 与 {@code @mermaid-js/mermaid-cli}，并确保运行账号可以启动
 * 无头 Chromium。容器内建议配置 {@code --no-sandbox}，最好把渲染放在独立进程/容器中。</p>
 */
@Slf4j
@Component
public class MermaidCliRenderer implements MermaidRenderer {

    private static final int MAX_OUTPUT_BYTES = 15 * 1024 * 1024;

    private static final int MAX_LOG_CHARS = 8 * 1024;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};

    private final WorkflowProperties.Mermaid config;

    /**
     * 缓存命令可用性，避免每次渲染都启动一次 --version 探测。
     */
    private volatile Boolean available;

    public MermaidCliRenderer(WorkflowProperties properties) {
        this.config = properties.getMermaid();
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available != null) {
                return available;
            }
            available = probeCommand();
            if (!available) {
                log.warn("Mermaid CLI 不可用，架构图生成将被跳过。请安装 @mermaid-js/mermaid-cli 或修改 app.workflow.mermaid.command");
            }
            return available;
        }
    }

    @Override
    public RenderedDiagram render(String mermaidCode) throws IOException {
        if (StrUtil.isBlank(mermaidCode)) {
            throw new IOException("Mermaid 代码为空");
        }
        if (!config.isEnabled()) {
            throw new IOException("Mermaid 架构图功能已关闭");
        }
        String format = normalizeFormat(config.getOutputFormat());
        Path tempDir = Files.createTempDirectory("zgq-mermaid-");
        Path inputFile = tempDir.resolve("diagram.mmd");
        Path outputFile = tempDir.resolve("diagram." + format);
        Path puppeteerConfig = tempDir.resolve("puppeteer-config.json");
        try {
            Files.writeString(inputFile, mermaidCode, StandardCharsets.UTF_8);
            Files.writeString(puppeteerConfig, buildPuppeteerConfig(), StandardCharsets.UTF_8);

            List<String> command = buildCommand(inputFile, outputFile, puppeteerConfig);
            ProcessResult result = execute(command, tempDir, config.getTimeout());
            if (!result.success()) {
                throw new IOException("Mermaid CLI 渲染失败，退出码 " + result.exitCode()
                        + "，输出：" + StrUtil.maxLength(result.output(), 500));
            }
            validateOutput(outputFile, format);
            log.info("Mermaid 图表渲染成功，format={}，size={} bytes", format, Files.size(outputFile));
            return new RenderedDiagram(outputFile, format, tempDir);
        } catch (IOException | RuntimeException e) {
            FileUtil.del(tempDir.toFile());
            throw e;
        }
    }

    /**
     * 构造 mmdc 参数。使用参数列表而非字符串命令，避免关键词或代码中的字符被 shell 解释。
     */
    List<String> buildCommand(Path inputFile, Path outputFile, Path puppeteerConfig) {
        List<String> command = new ArrayList<>();
        command.add(StrUtil.blankToDefault(config.getCommand(), "mmdc").trim());
        command.add("-i");
        command.add(inputFile.toAbsolutePath().toString());
        command.add("-o");
        command.add(outputFile.toAbsolutePath().toString());
        command.add("-b");
        command.add(StrUtil.blankToDefault(config.getBackground(), "transparent"));
        command.add("-t");
        command.add(StrUtil.blankToDefault(config.getTheme(), "default"));
        command.add("-w");
        command.add(String.valueOf(Math.max(200, config.getWidth())));
        command.add("-H");
        command.add(String.valueOf(Math.max(200, config.getHeight())));
        command.add("-p");
        command.add(puppeteerConfig.toAbsolutePath().toString());
        return command;
    }

    private String buildPuppeteerConfig() {
        Map<String, Object> configMap = new LinkedHashMap<>();
        List<String> args = new ArrayList<>();
        if (StrUtil.isNotBlank(config.getPuppeteerArgs())) {
            Arrays.stream(config.getPuppeteerArgs().split(","))
                    .map(String::trim)
                    .filter(StrUtil::isNotBlank)
                    .forEach(args::add);
        }
        if (!args.isEmpty()) {
            configMap.put("args", args);
        }
        if (StrUtil.isNotBlank(config.getPuppeteerExecutablePath())) {
            configMap.put("executablePath", config.getPuppeteerExecutablePath().trim());
        }
        return JSONUtil.toJsonStr(configMap);
    }

    private ProcessResult execute(List<String> command, Path workingDir,
                                  java.time.Duration timeout) throws IOException {
        List<String> actualCommand = wrapWithCommandInterpreterIfNeeded(command);
        Process process = null;
        Thread outputReader = null;
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(actualCommand)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true);
            log.debug("执行 Mermaid CLI：{}", String.join(" ", actualCommand));
            process = processBuilder.start();
            Process startedProcess = process;
            outputReader = Thread.ofVirtual()
                    .name("mermaid-cli-output")
                    .start(() -> captureOutput(startedProcess, output));
            long timeoutMillis = timeout == null ? 120_000L : Math.max(1_000L, timeout.toMillis());
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                return new ProcessResult(false, -1, "渲染超时（" + timeoutMillis + " ms）");
            }
            joinQuietly(outputReader);
            int exitCode = process.exitValue();
            String captured;
            synchronized (output) {
                captured = output.toString();
            }
            return new ProcessResult(exitCode == 0, exitCode, captured);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            throw new IOException("Mermaid CLI 执行被中断", e);
        } finally {
            destroyProcessTree(process);
            if (outputReader != null) {
                outputReader.interrupt();
            }
        }
    }

    /**
     * 读取进程输出，只保留前若干字符。读取线程不能抛出异常。
     */
    private static void captureOutput(Process process, StringBuilder output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                synchronized (output) {
                    if (output.length() < MAX_LOG_CHARS) {
                        output.append(buffer, 0, Math.min(read, MAX_LOG_CHARS - output.length()));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("读取 Mermaid CLI 输出失败", e);
        }
    }

    private boolean probeCommand() {
        String command = StrUtil.blankToDefault(config.getCommand(), "mmdc").trim();
        if (command.isEmpty()) {
            return false;
        }
        List<String> actualCommand = wrapWithCommandInterpreterIfNeeded(List.of(command, "--version"));
        try {
            Process process = new ProcessBuilder(actualCommand).redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                destroyProcessTree(process);
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Mermaid CLI 可用性检测失败", e);
            return false;
        }
    }

    private static void validateOutput(Path outputFile, String format) throws IOException {
        if (!Files.isRegularFile(outputFile)) {
            throw new IOException("Mermaid CLI 未生成图片文件");
        }
        long size = Files.size(outputFile);
        if (size <= 0 || size > MAX_OUTPUT_BYTES) {
            throw new IOException("Mermaid 图片大小不合法：" + size);
        }
        byte[] head = new byte[4];
        try (var input = Files.newInputStream(outputFile)) {
            int read = input.read(head);
            if ("png".equals(format)) {
                if (read < 4 || !Arrays.equals(head, PNG_MAGIC)) {
                    throw new IOException("Mermaid 输出不是合法 PNG");
                }
            }
        }
        if ("svg".equals(format)) {
            String content = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (!content.contains("<svg")) {
                throw new IOException("Mermaid 输出不是合法 SVG");
            }
        }
    }

    private static String normalizeFormat(String format) {
        String normalized = StrUtil.blankToDefault(format, "png").trim().toLowerCase(Locale.ROOT);
        return "svg".equals(normalized) ? "svg" : "png";
    }

    private static List<String> wrapWithCommandInterpreterIfNeeded(List<String> command) {
        if (command.isEmpty()) {
            return command;
        }
        String executable = command.get(0);
        String lower = executable.toLowerCase(Locale.ROOT);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
        if (windows && !(lower.endsWith(".cmd") || lower.endsWith(".bat") || lower.endsWith(".exe"))) {
            // npm 的全局命令在 Windows 下以 .cmd 提供，直接 CreateProcess 会找不到文件。
            List<String> withExtension = new ArrayList<>(command);
            withExtension.set(0, executable + ".cmd");
            command = withExtension;
            lower = command.get(0).toLowerCase(Locale.ROOT);
        }
        if (!windows || (!lower.endsWith(".cmd") && !lower.endsWith(".bat"))) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(command.size() + 2);
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 进程执行结果。
     */
    private record ProcessResult(boolean success, int exitCode, String output) {
    }
}
