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
 * {@link FileReadTool} 的确定性单元测试：不调用模型，直接在临时工程目录中读写文件。
 */
class FileReadToolTest {

    private final FileReadTool fileReadTool = new FileReadTool();

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private final Path projectRoot = FileWriteTool.projectRoot(appId);

    @AfterEach
    void removeTestProject() {
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    void readsFileWithRelativePathAndContent() throws IOException {
        String content = "<template><h1>你好</h1></template>";
        writeFile("src/App.vue", content);

        String result = fileReadTool.readFile("src/App.vue", appId);

        assertEquals("文件读取成功：src/App.vue（" + content.length() + " 字符）\n文件内容：\n" + content,
                result);
        assertFalse(result.contains(projectRoot.toString()), "工具不能把服务器绝对路径返回给模型");
        assertFalse(result.contains("code_output"), "工具不能返回存储根目录");
    }

    @Test
    void readsEmptyFileAndKeepsRelativePath() throws IOException {
        writeFile("src/Empty.vue", "");

        String result = fileReadTool.readFile("src/Empty.vue", appId);

        assertTrue(result.startsWith("文件读取成功：src/Empty.vue（0 字符）"), result);
        assertTrue(result.contains("文件内容为空"), result);
    }

    @Test
    void stripsProjectDirectoryPrefixAndBackslashes() throws IOException {
        writeFile("package.json", "{\"name\":\"demo\"}");

        String result = fileReadTool.readFile("./vue_project_" + appId + "\\package.json", appId);

        assertTrue(result.startsWith("文件读取成功：package.json"), result);
        assertTrue(result.contains("{\"name\":\"demo\"}"), result);
    }

    @Test
    void truncatesOversizedFileWithoutChangingIt() throws IOException {
        String content = "x".repeat(FileReadTool.MAX_READ_CONTENT_LENGTH + 128);
        Path file = writeFile("src/Large.vue", content);

        String result = fileReadTool.readFile("src/Large.vue", appId);

        assertTrue(result.contains("文件内容过长，以下仅展示前 "
                + FileReadTool.MAX_READ_CONTENT_LENGTH + " 字符"), result);
        assertTrue(result.length() < content.length(), "截断后的结果不应包含完整文件内容");
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8), "读取工具不能修改文件");
    }

    @Test
    void reportsMissingFileWithoutExposingAbsolutePath() {
        String result = fileReadTool.readFile("src/Missing.vue", appId);

        assertTrue(result.startsWith("文件读取失败："), result);
        assertTrue(result.contains("文件不存在"), result);
        assertFalse(result.contains(projectRoot.toString()), "失败信息不能泄露绝对路径");
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        Path escapedFile = projectRoot.getParent().resolve("read-escaped-" + appId + ".txt");
        List<String> invalidPaths = List.of(
                "../read-escaped-" + appId + ".txt",
                "src/../../read-escaped-" + appId + ".txt",
                "/etc/passwd",
                "\\\\server\\share\\evil.txt",
                "C:\\Windows\\win.ini",
                "."
        );

        for (String invalidPath : invalidPaths) {
            String result = fileReadTool.readFile(invalidPath, appId);
            assertTrue(result.startsWith("文件读取失败："), invalidPath + " 应被拒绝，实际：" + result);
            assertFalse(result.contains(projectRoot.toString()), "失败信息不能泄露绝对路径");
        }
        assertFalse(Files.exists(escapedFile));
    }

    @Test
    void rejectsInvalidAppIdAndBlankPath() {
        assertTrue(fileReadTool.readFile("src/App.vue", null).contains("应用 ID 无效"));
        assertTrue(fileReadTool.readFile("src/App.vue", 0L).contains("应用 ID 无效"));
        assertTrue(fileReadTool.readFile(" ", appId).contains("文件路径不能为空"));
        assertTrue(fileReadTool.readFile(null, appId).contains("文件路径不能为空"));
        assertTrue(fileReadTool.readFile("src/", appId).contains("文件路径不能为空"));
    }

    private Path writeFile(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
