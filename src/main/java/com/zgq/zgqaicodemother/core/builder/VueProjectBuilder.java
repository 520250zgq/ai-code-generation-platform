package com.zgq.zgqaicodemother.core.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Vue 工程构建器。
 *
 * <p>代码生成只负责写出工程源码，Vue 工程还需要安装依赖并打包出 {@code dist} 目录后才能浏览。
 * 本类封装完整的构建流程：检测操作系统、构造 npm 命令、执行命令、校验 dist 产物。</p>
 *
 * <p>构建属于耗时操作，调用方应使用 {@link #buildProjectAsync(String)} 在虚拟线程中执行，
 * 避免阻塞 SSE 主流程。所有构建方法都尽量以返回 {@code false} 的方式报告失败，不向业务
 * 主流程抛出异常。</p>
 */
@Slf4j
@Component
public class VueProjectBuilder {

    /**
     * npm install 的超时时间：首次安装依赖受网络影响，给 5 分钟。
     */
    static final int NPM_INSTALL_TIMEOUT_SECONDS = 300;

    /**
     * npm run build 的超时时间：Vite 打包通常在 1 分钟内完成，给 3 分钟。
     */
    static final int NPM_BUILD_TIMEOUT_SECONDS = 180;

    /**
     * 只保留命令输出的最后若干行，避免 npm 日志占满内存。
     */
    private static final int MAX_CAPTURED_OUTPUT_LINES = 200;

    /**
     * 正在构建的项目路径，避免同一工程被并发构建（例如用户快速连续发起两轮生成）。
     */
    private final Set<String> buildingProjects = ConcurrentHashMap.newKeySet();

    /**
     * 构建期间追加的构建请求。
     *
     * <p>增量修改可能在上一轮构建尚未结束时完成。如果直接丢弃新的构建请求，预览会一直停留在
     * 旧版本；因此把请求排队，等当前构建结束后用最新源码再构建一次。</p>
     */
    private final Set<String> pendingBuilds = ConcurrentHashMap.newKeySet();

    /**
     * 构建结束后的回调，按工程路径登记。
     *
     * <p>用于把“构建完成”同步到预览状态服务。同一工程重复提交构建请求时只保留最新回调：
     * 回调做的事情一致（标记预览就绪或失败），保留最新回调可以避免为同一结果登记多份通知。</p>
     */
    private final Map<String, Consumer<Boolean>> completionCallbacks = new ConcurrentHashMap<>();

    /**
     * 构建状态和排队集合的调度锁，保证“检查占用 + 登记排队”和“结束占用 + 检查排队”不会交错。
     */
    private final Object buildSchedulingLock = new Object();

    /**
     * 在虚拟线程中异步构建 Vue 项目。
     *
     * <p>方法本身立即返回，构建异常只会记录日志，不会影响主业务流程。同一工程已有构建任务时
     * 不会并发执行，而是把本次请求排入队列；当前构建结束后会基于最新源码再构建一次，
     * 保证生成或增量修改后预览都能更新到最新版本。</p>
     *
     * @param projectPath Vue 工程根目录
     */
    public void buildProjectAsync(String projectPath) {
        buildProjectAsync(projectPath, null);
    }

    /**
     * 在虚拟线程中异步构建 Vue 项目，并在构建（含排队的后续构建）全部结束时回调结果。
     *
     * <p>回调只会在构建循环真正结束时触发一次，参数是最后一轮构建是否成功。
     * 回调内部抛出的异常会被捕获并记录，不能影响构建线程。</p>
     *
     * @param projectPath        Vue 工程根目录
     * @param completionCallback 构建结束回调，允许为 null
     */
    public void buildProjectAsync(String projectPath, Consumer<Boolean> completionCallback) {
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("跳过 Vue 项目构建：工程路径为空");
            notifyCompletion(null, false, completionCallback);
            return;
        }
        String normalizedPath = new File(projectPath).getAbsolutePath();
        if (completionCallback != null) {
            completionCallbacks.put(normalizedPath, completionCallback);
        }
        synchronized (buildSchedulingLock) {
            if (!buildingProjects.add(normalizedPath)) {
                pendingBuilds.add(normalizedPath);
                log.info("Vue 项目正在构建中，本次构建请求已排队：{}", normalizedPath);
                return;
            }
        }
        startBuildLoop(normalizedPath);
    }

    /**
     * 启动虚拟线程执行构建循环。
     *
     * @param normalizedPath 归一化后的工程绝对路径
     */
    private void startBuildLoop(String normalizedPath) {
        try {
            Thread.ofVirtual()
                    .name("vue-builder-" + new File(normalizedPath).getName())
                    .start(() -> runBuildLoop(normalizedPath));
        } catch (Exception e) {
            synchronized (buildSchedulingLock) {
                buildingProjects.remove(normalizedPath);
                pendingBuilds.remove(normalizedPath);
            }
            log.error("创建 Vue 构建虚拟线程失败：{}", normalizedPath, e);
            notifyCompletion(normalizedPath, false, null);
        }
    }

    /**
     * 构建循环：每轮开始前清除排队标记，构建结束后若又有新请求则继续下一轮。
     *
     * @param normalizedPath 归一化后的工程绝对路径
     */
    private void runBuildLoop(String normalizedPath) {
        boolean lastSuccess = false;
        try {
            while (true) {
                synchronized (buildSchedulingLock) {
                    // 本轮构建会读取磁盘上的最新源码，之前登记的排队请求已经包含在内。
                    pendingBuilds.remove(normalizedPath);
                }
                try {
                    lastSuccess = buildProject(normalizedPath);
                } catch (Exception e) {
                    // 异步线程不能把异常抛回主流程，最多记录日志。
                    log.error("异步构建 Vue 项目失败：{}", normalizedPath, e);
                    lastSuccess = false;
                }
                synchronized (buildSchedulingLock) {
                    if (!pendingBuilds.remove(normalizedPath)) {
                        buildingProjects.remove(normalizedPath);
                        break;
                    }
                    log.info("检测到新的构建请求，使用最新源码重新构建：{}", normalizedPath);
                }
            }
        } catch (RuntimeException | Error e) {
            synchronized (buildSchedulingLock) {
                buildingProjects.remove(normalizedPath);
                pendingBuilds.remove(normalizedPath);
            }
            log.error("Vue 项目构建循环异常终止：{}", normalizedPath, e);
            lastSuccess = false;
        } finally {
            notifyCompletion(normalizedPath, lastSuccess, null);
        }
    }

    /**
     * 触发构建完成回调，并清理登记信息。
     *
     * @param projectPath  工程路径，用于取出登记的回调
     * @param success      最后一轮构建是否成功
     * @param directCallback 直接指定的回调，优先级高于登记表中的回调
     */
    private void notifyCompletion(String projectPath, boolean success, Consumer<Boolean> directCallback) {
        Consumer<Boolean> callback = directCallback;
        if (projectPath != null) {
            Consumer<Boolean> registered = completionCallbacks.remove(projectPath);
            if (registered != null) {
                callback = registered;
            }
        }
        if (callback == null) {
            return;
        }
        try {
            callback.accept(success);
        } catch (Exception e) {
            // 回调通常用于更新预览状态，失败只影响前端刷新时机，不能影响构建结果。
            log.error("Vue 构建完成回调执行失败：{}", projectPath, e);
        }
    }

    /**
     * 同步构建 Vue 项目：校验工程、安装依赖、打包，并确认 dist 目录生成。
     *
     * <p>任何步骤失败都返回 {@code false}，不抛出异常；调用方可以根据返回值决定是否提示用户。</p>
     *
     * @param projectPath Vue 工程根目录
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        try {
            return doBuildProject(projectPath);
        } catch (Exception e) {
            log.error("构建 Vue 项目时发生异常：{}", projectPath, e);
            return false;
        }
    }

    private boolean doBuildProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            log.error("Vue 项目构建失败：工程路径为空");
            return false;
        }
        File projectDir = new File(projectPath);
        if (!projectDir.isDirectory()) {
            log.error("Vue 项目构建失败：工程目录不存在：{}", projectDir.getAbsolutePath());
            return false;
        }
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.isFile()) {
            log.error("Vue 项目构建失败：工程目录缺少 package.json：{}", projectDir.getAbsolutePath());
            return false;
        }
        log.info("开始构建 Vue 项目：{}", projectDir.getAbsolutePath());
        if (!executeNpmInstall(projectDir)) {
            log.error("Vue 项目构建失败：npm install 执行失败：{}", projectDir.getAbsolutePath());
            return false;
        }
        if (!executeNpmBuild(projectDir)) {
            log.error("Vue 项目构建失败：npm run build 执行失败：{}", projectDir.getAbsolutePath());
            return false;
        }
        File distDir = new File(projectDir, "dist");
        if (!distDir.isDirectory()) {
            log.error("Vue 项目构建失败：命令执行完成但未生成 dist 目录：{}", projectDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist 目录：{}", distDir.getAbsolutePath());
        return true;
    }

    /**
     * 执行依赖安装命令。
     *
     * <p>使用 {@code --ignore-scripts} 禁止执行依赖包的生命周期脚本，避免 AI 生成的
     * package.json 借 npm install 执行任意服务器命令；Vue/Vite 依赖不需要这些脚本。</p>
     *
     * @param projectDir Vue 工程根目录
     * @return 是否成功
     */
    boolean executeNpmInstall(File projectDir) {
        log.info("执行 npm install：{}", projectDir.getAbsolutePath());
        return executeCommand(projectDir,
                buildCommand("npm", "install", "--ignore-scripts", "--no-audit", "--no-fund"),
                NPM_INSTALL_TIMEOUT_SECONDS);
    }

    /**
     * 执行打包构建命令。
     *
     * @param projectDir Vue 工程根目录
     * @return 是否成功
     */
    boolean executeNpmBuild(File projectDir) {
        log.info("执行 npm run build：{}", projectDir.getAbsolutePath());
        return executeCommand(projectDir, buildCommand("npm", "run", "build"), NPM_BUILD_TIMEOUT_SECONDS);
    }

    /**
     * 检测当前操作系统是否为 Windows。
     *
     * @return Windows 返回 true
     */
    static boolean isWindows() {
        String osName = System.getProperty("os.name");
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * 根据操作系统构造可执行命令。
     *
     * <p>Windows 下 npm 以 {@code npm.cmd} 提供，直接执行会失败；Unix 下使用 {@code npm}。</p>
     *
     * @param baseCommand 基础命令，例如 npm
     * @param args        命令参数
     * @return 不可变的命令列表
     */
    static List<String> buildCommand(String baseCommand, String... args) {
        if (baseCommand == null || baseCommand.isBlank()) {
            return List.of();
        }
        String executable = baseCommand;
        String lowerCase = executable.toLowerCase(Locale.ROOT);
        if (isWindows() && !lowerCase.endsWith(".cmd") && !lowerCase.endsWith(".bat")
                && !lowerCase.endsWith(".exe")) {
            executable += ".cmd";
        }
        List<String> command = new ArrayList<>();
        command.add(executable);
        if (args != null && args.length > 0) {
            command.addAll(Arrays.asList(args));
        }
        return List.copyOf(command);
    }

    /**
     * 执行任意命令的通用方法。
     *
     * <p>命令在指定工作目录中执行，标准输出和错误输出合并读取，避免 npm 大量日志填满管道导致
     * 进程阻塞。超时后会强制终止进程。任何异常都会被捕获并返回 {@code false}。</p>
     *
     * @param workingDir     工作目录
     * @param command        命令及参数
     * @param timeoutSeconds 超时时间（秒）
     * @return 退出码为 0 时返回 true
     */
    boolean executeCommand(File workingDir, List<String> command, int timeoutSeconds) {
        if (workingDir == null || !workingDir.isDirectory()) {
            log.error("命令执行失败：工作目录不存在：{}", workingDir);
            return false;
        }
        if (command == null || command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) {
            log.error("命令执行失败：命令为空");
            return false;
        }
        List<String> actualCommand = wrapWithCommandInterpreterIfNeeded(command);
        Process process = null;
        Thread outputReader = null;
        Deque<String> output = new ArrayDeque<>();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(actualCommand)
                    .directory(workingDir)
                    .redirectErrorStream(true);
            log.info("在目录 {} 中执行命令：{}", workingDir.getAbsolutePath(), String.join(" ", actualCommand));
            process = processBuilder.start();
            Process startedProcess = process;
            // 必须在 waitFor 之前持续读取输出，否则输出管道写满后 npm 会一直阻塞。
            outputReader = Thread.ofVirtual()
                    .name("vue-builder-output")
                    .start(() -> captureOutput(startedProcess, output));

            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{} 秒），强制终止：{}", timeoutSeconds, String.join(" ", actualCommand));
                destroyProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                return false;
            }
            joinOutputReader(outputReader);
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功：{}", String.join(" ", actualCommand));
                log.debug("命令输出：\n{}", tailOf(output));
                return true;
            }
            log.error("命令执行失败，退出码：{}，命令：{}，输出：\n{}",
                    exitCode, String.join(" ", actualCommand), tailOf(output));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            log.error("命令执行被中断：{}", String.join(" ", actualCommand));
            return false;
        } catch (Exception e) {
            destroyProcessTree(process);
            log.error("命令执行异常：{}，错误：{}", String.join(" ", actualCommand), e.getMessage(), e);
            return false;
        } finally {
            if (outputReader != null && outputReader.isAlive()) {
                outputReader.interrupt();
            }
        }
    }

    /**
     * Windows 下 {@code .cmd}/{@code .bat} 不是可执行文件，需要交给 cmd.exe 执行。
     */
    private static List<String> wrapWithCommandInterpreterIfNeeded(List<String> command) {
        String executable = command.get(0);
        String lowerCase = executable.toLowerCase(Locale.ROOT);
        if (!isWindows() || (!lowerCase.endsWith(".cmd") && !lowerCase.endsWith(".bat"))) {
            return command;
        }
        List<String> wrapped = new ArrayList<>(command.size() + 2);
        wrapped.add("cmd.exe");
        wrapped.add("/c");
        wrapped.addAll(command);
        return wrapped;
    }

    /**
     * 持续读取进程输出，只保留最后若干行。读取线程本身不能抛出异常。
     */
    private static void captureOutput(Process process, Deque<String> output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (output) {
                    output.addLast(line);
                    if (output.size() > MAX_CAPTURED_OUTPUT_LINES) {
                        output.removeFirst();
                    }
                }
            }
        } catch (IOException e) {
            log.debug("读取构建命令输出失败", e);
        }
    }

    private static void joinOutputReader(Thread outputReader) {
        if (outputReader == null) {
            return;
        }
        try {
            outputReader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String tailOf(Deque<String> output) {
        synchronized (output) {
            return String.join("\n", output);
        }
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        // npm 在 Windows 下会派生 node 子进程，只杀 cmd.exe 可能留下孤儿进程。
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
