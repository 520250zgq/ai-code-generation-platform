package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileWriteTool} 的确定性单元测试：不调用模型、不依赖 Spring 容器。
 */
class FileWriteToolTest {

    private final FileWriteTool fileWriteTool = new FileWriteTool();

    /**
     * 每个测试使用独立的 appId，避免覆盖真实应用的工程目录。
     */
    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private final Path projectRoot = FileWriteTool.projectRoot(appId);

    @AfterEach
    void removeTestProject() {
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    void writesNestedFileAndReturnsOnlyRelativePath() throws IOException {
        String content = "<template><header>你好</header></template>\n";

        String result = fileWriteTool.writeFile("src/components/Header.vue", content, appId);

        assertEquals("文件写入成功：src/components/Header.vue", result);
        assertFalse(result.contains(projectRoot.toString()), "工具不能把服务器绝对路径返回给模型");
        assertFalse(result.contains("tmp/code_output"), "工具不能返回存储根目录");
        Path savedFile = projectRoot.resolve("src/components/Header.vue");
        assertTrue(Files.isRegularFile(savedFile));
        assertEquals(content, Files.readString(savedFile, StandardCharsets.UTF_8));
    }

    @Test
    void normalizesBackslashesAndProjectDirectoryPrefix() throws IOException {
        // 模型偶尔会带 ./、工程目录名或 Windows 反斜杠，工具应做兼容而不是写到嵌套目录。
        String result = fileWriteTool.writeFile(
                "./vue_project_" + appId + "\\src\\App.vue", "<template><div /></template>", appId);

        assertEquals("文件写入成功：src/App.vue", result);
        assertTrue(Files.isRegularFile(projectRoot.resolve("src/App.vue")));
        assertFalse(Files.exists(projectRoot.resolve("vue_project_" + appId)), "不能写入嵌套工程目录");
    }

    @Test
    void overwritesExistingFile() throws IOException {
        assertEquals("文件写入成功：package.json",
                fileWriteTool.writeFile("package.json", "{\"version\":1}", appId));
        assertEquals("文件写入成功：package.json",
                fileWriteTool.writeFile("package.json", "{\"version\":2}", appId));

        assertEquals("{\"version\":2}",
                Files.readString(projectRoot.resolve("package.json"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        Path escapedFile = projectRoot.getParent().resolve("escaped-" + appId + ".txt");
        List<String> invalidPaths = List.of(
                "../escaped-" + appId + ".txt",
                "src/../../escaped-" + appId + ".txt",
                "/etc/passwd",
                "\\\\server\\share\\evil.txt",
                "C:\\Windows\\win.ini",
                "C:escaped.txt",
                "."
        );

        for (String invalidPath : invalidPaths) {
            String result = fileWriteTool.writeFile(invalidPath, "hacked", appId);
            assertTrue(result.startsWith("文件写入失败："), invalidPath + " 应被拒绝，实际：" + result);
            assertFalse(result.contains(projectRoot.toString()), "失败信息不能泄露绝对路径");
        }
        assertFalse(Files.exists(escapedFile), "路径穿越不能写出工程目录");
        assertFalse(Files.exists(projectRoot.resolve("escaped-" + appId + ".txt")));
    }

    @Test
    void rejectsBlankPathInvalidAppIdAndNullContent() {
        assertTrue(fileWriteTool.writeFile(" ", "content", appId).contains("路径不能为空"));
        assertTrue(fileWriteTool.writeFile(null, "content", appId).contains("路径不能为空"));
        assertTrue(fileWriteTool.writeFile("src/", "content", appId).contains("路径不能为空"));
        assertTrue(fileWriteTool.writeFile("src/App.vue", "content", null).contains("应用 ID 无效"));
        assertTrue(fileWriteTool.writeFile("src/App.vue", "content", 0L).contains("应用 ID 无效"));
        assertTrue(fileWriteTool.writeFile("src/App.vue", null, appId).contains("文件内容不能为空"));
        assertTrue(fileWriteTool.writeFile("src/App.vue", " \n\t ", appId).contains("文件内容不能为空"));
        assertFalse(Files.exists(projectRoot.resolve("src/App.vue")));
    }

    @Test
    void rejectsOversizedContentWithoutWritingFile() {
        String oversized = "x".repeat(FileWriteTool.MAX_CONTENT_LENGTH + 1);

        String result = fileWriteTool.writeFile("src/App.vue", oversized, appId);

        assertTrue(result.contains("超过单文件上限"), result);
        assertFalse(Files.exists(projectRoot.resolve("src/App.vue")));
    }
}
