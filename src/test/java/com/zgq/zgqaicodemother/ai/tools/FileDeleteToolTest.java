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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileDeleteTool} 的确定性单元测试：验证单文件删除、幂等和目录保护。
 */
class FileDeleteToolTest {

    private final FileDeleteTool fileDeleteTool = new FileDeleteTool();

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private final Path projectRoot = FileWriteTool.projectRoot(appId);

    @AfterEach
    void removeTestProject() {
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    void deletesSingleFileAndKeepsOtherFiles() throws IOException {
        Path target = writeFile("src/components/OldHeader.vue", "<template><header /></template>");
        Path keep = writeFile("src/components/NewHeader.vue", "<template><header /></template>");

        String result = fileDeleteTool.deleteFile("src/components/OldHeader.vue", appId);

        assertTrue(result.startsWith("文件删除成功：src/components/OldHeader.vue"), result);
        assertFalse(Files.exists(target));
        assertTrue(Files.isRegularFile(keep), "不能误删其他文件");
        assertFalse(result.contains(projectRoot.toString()), "工具不能返回服务器绝对路径");
    }

    @Test
    void treatsMissingFileAsAlreadyDeleted() {
        String result = fileDeleteTool.deleteFile("src/components/Missing.vue", appId);

        assertTrue(result.startsWith("文件删除成功："), result);
        assertTrue(result.contains("文件原本不存在"), result);
    }

    @Test
    void refusesToDeleteDirectory() throws IOException {
        Path directory = projectRoot.resolve("src/components");
        Files.createDirectories(directory);
        writeFile("src/components/Header.vue", "<template><header /></template>");

        String result = fileDeleteTool.deleteFile("src/components", appId);

        assertTrue(result.startsWith("文件删除失败："), result);
        assertTrue(result.contains("该路径是目录"), result);
        assertTrue(Files.isDirectory(directory), "目录不能被删除");
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() throws IOException {
        Path escapedFile = projectRoot.getParent().resolve("delete-escaped-" + appId + ".txt");
        Files.writeString(escapedFile, "outside", StandardCharsets.UTF_8);
        writeFile("src/App.vue", "<div />");
        List<String> invalidPaths = List.of(
                "../delete-escaped-" + appId + ".txt",
                "src/../../delete-escaped-" + appId + ".txt",
                "/etc/passwd",
                "C:\\Windows\\win.ini",
                "C:escaped.txt",
                "."
        );

        for (String invalidPath : invalidPaths) {
            String result = fileDeleteTool.deleteFile(invalidPath, appId);
            assertTrue(result.startsWith("文件删除失败："), invalidPath + " 应被拒绝，实际：" + result);
            assertFalse(result.contains(projectRoot.toString()), "失败信息不能泄露绝对路径");
        }
        assertTrue(Files.exists(escapedFile), "路径穿越不能删除工程外文件");
    }

    @Test
    void rejectsInvalidAppIdAndBlankPath() {
        assertTrue(fileDeleteTool.deleteFile("src/App.vue", null).contains("应用 ID 无效"));
        assertTrue(fileDeleteTool.deleteFile("src/App.vue", 0L).contains("应用 ID 无效"));
        assertTrue(fileDeleteTool.deleteFile(" ", appId).contains("文件路径不能为空"));
        // “.” 会解析到工程根目录，必须被拒绝，不能删除整个工程。
        assertTrue(fileDeleteTool.deleteFile(".", appId).contains("路径必须位于当前应用工程目录内"));
    }

    @Test
    void stripsProjectDirectoryPrefix() throws IOException {
        Path target = writeFile("src/App.vue", "<div />");

        String result = fileDeleteTool.deleteFile("vue_project_" + appId + "/src/App.vue", appId);

        assertTrue(result.startsWith("文件删除成功：src/App.vue"), result);
        assertFalse(Files.exists(target));
    }

    private Path writeFile(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
