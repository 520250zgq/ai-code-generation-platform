package com.zgq.zgqaicodemother.core.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Vue 构建器的确定性单元测试：不执行真实 npm，通过 spy 验证构建流程和失败降级。
 */
class VueProjectBuilderTest {

    @TempDir
    private Path tempDir;

    private final VueProjectBuilder builder = new VueProjectBuilder();

    @Test
    void buildsPlatformSpecificNpmCommand() {
        List<String> installCommand = VueProjectBuilder.buildCommand("npm", "install");

        assertEquals(VueProjectBuilder.isWindows() ? "npm.cmd" : "npm", installCommand.get(0));
        assertEquals(List.of("install"), installCommand.subList(1, installCommand.size()));

        List<String> buildCommand = VueProjectBuilder.buildCommand("npm", "run", "build");
        assertEquals(List.of("run", "build"), buildCommand.subList(1, buildCommand.size()));
        assertEquals("npm.cmd", VueProjectBuilder.buildCommand("npm.cmd").get(0));
        assertTrue(VueProjectBuilder.buildCommand(" ").isEmpty());
    }

    @Test
    void executeCommandReturnsTrueForZeroExitCode() {
        assertTrue(builder.executeCommand(tempDir.toFile(), exitCommand(0), 10));
    }

    @Test
    void executeCommandReturnsFalseForNonZeroExitCode() {
        assertFalse(builder.executeCommand(tempDir.toFile(), exitCommand(1), 10));
    }

    @Test
    void executeCommandReturnsFalseForMissingWorkingDirectoryOrCommand() {
        assertFalse(builder.executeCommand(tempDir.resolve("missing").toFile(), exitCommand(0), 10));
        assertFalse(builder.executeCommand(tempDir.toFile(), List.of(), 10));
        assertFalse(builder.executeCommand(tempDir.toFile(),
                List.of("zgq-not-a-real-command-123"), 10));
    }

    @Test
    void rejectsMissingProjectOrPackageJson() throws IOException {
        assertFalse(builder.buildProject(tempDir.resolve("missing").toString()));
        Path project = Files.createDirectory(tempDir.resolve("empty-project"));
        assertFalse(builder.buildProject(project.toString()));
    }

    @Test
    void successfulBuildRunsInstallAndBuildAndRequiresDist() throws IOException {
        Path project = createProject();
        Files.createDirectories(project.resolve("dist"));
        VueProjectBuilder spyBuilder = spy(new VueProjectBuilder());
        doReturn(true).when(spyBuilder).executeNpmInstall(any(File.class));
        doReturn(true).when(spyBuilder).executeNpmBuild(any(File.class));

        assertTrue(spyBuilder.buildProject(project.toString()));

        verify(spyBuilder).executeNpmInstall(any(File.class));
        verify(spyBuilder).executeNpmBuild(any(File.class));
    }

    @Test
    void installFailureSkipsBuildAndReturnsFalse() throws IOException {
        Path project = createProject();
        VueProjectBuilder spyBuilder = spy(new VueProjectBuilder());
        doReturn(false).when(spyBuilder).executeNpmInstall(any(File.class));

        assertFalse(spyBuilder.buildProject(project.toString()));

        verify(spyBuilder).executeNpmInstall(any(File.class));
        verify(spyBuilder, never()).executeNpmBuild(any(File.class));
    }

    @Test
    void buildFailureReturnsFalse() throws IOException {
        Path project = createProject();
        Files.createDirectories(project.resolve("dist"));
        VueProjectBuilder spyBuilder = spy(new VueProjectBuilder());
        doReturn(true).when(spyBuilder).executeNpmInstall(any(File.class));
        doReturn(false).when(spyBuilder).executeNpmBuild(any(File.class));

        assertFalse(spyBuilder.buildProject(project.toString()));
    }

    @Test
    void missingDistReturnsFalse() throws IOException {
        Path project = createProject();
        VueProjectBuilder spyBuilder = spy(new VueProjectBuilder());
        doReturn(true).when(spyBuilder).executeNpmInstall(any(File.class));
        doReturn(true).when(spyBuilder).executeNpmBuild(any(File.class));

        assertFalse(spyBuilder.buildProject(project.toString()));
    }

    @Test
    void unexpectedBuildExceptionIsConvertedToFalse() throws IOException {
        Path project = createProject();
        VueProjectBuilder spyBuilder = spy(new VueProjectBuilder());
        doThrow(new IllegalStateException("npm crashed"))
                .when(spyBuilder).executeNpmInstall(any(File.class));

        assertFalse(assertDoesNotThrow(() -> spyBuilder.buildProject(project.toString())));
    }

    @Test
    void asyncBuildRunsOnJava21VirtualThread() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean virtualThread = new AtomicBoolean(false);
        VueProjectBuilder asyncBuilder = new VueProjectBuilder() {
            @Override
            public boolean buildProject(String projectPath) {
                virtualThread.set(Thread.currentThread().isVirtual());
                latch.countDown();
                return true;
            }
        };

        asyncBuilder.buildProjectAsync(tempDir.toString());

        assertTrue(latch.await(5, TimeUnit.SECONDS), "异步构建没有在预期时间内启动");
        assertTrue(virtualThread.get(), "构建必须在 Java 21 虚拟线程中执行");
    }

    @Test
    void asyncBuildQueuesAnotherBuildWhenProjectIsBusy() throws InterruptedException {
        CountDownLatch firstBuildStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstBuild = new CountDownLatch(1);
        AtomicInteger buildCount = new AtomicInteger();
        VueProjectBuilder asyncBuilder = new VueProjectBuilder() {
            @Override
            public boolean buildProject(String projectPath) {
                int currentBuild = buildCount.incrementAndGet();
                if (currentBuild == 1) {
                    firstBuildStarted.countDown();
                    try {
                        releaseFirstBuild.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return true;
            }
        };

        asyncBuilder.buildProjectAsync(tempDir.toString());
        assertTrue(firstBuildStarted.await(5, TimeUnit.SECONDS), "首次构建没有在预期时间内启动");
        // 首轮构建尚未结束时又完成了一轮增量修改，本次请求必须排队而不是被丢弃。
        asyncBuilder.buildProjectAsync(tempDir.toString());
        releaseFirstBuild.countDown();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (buildCount.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, buildCount.get(), "构建期间的修改应排队，并在首轮结束后用最新源码再构建一次");
    }

    @Test
    void asyncBuildIgnoresBlankProjectPath() {
        assertDoesNotThrow(() -> builder.buildProjectAsync(" "));
        assertDoesNotThrow(() -> builder.buildProjectAsync(null));
    }

    private Path createProject() throws IOException {
        Path project = Files.createDirectory(tempDir.resolve("vue-project-" + System.nanoTime()));
        Files.writeString(project.resolve("package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");
        return project;
    }

    private static List<String> exitCommand(int exitCode) {
        return VueProjectBuilder.isWindows()
                ? List.of("cmd.exe", "/c", "exit", String.valueOf(exitCode))
                : List.of("sh", "-c", "exit " + exitCode);
    }
}
