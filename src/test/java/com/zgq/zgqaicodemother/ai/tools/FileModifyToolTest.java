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
 * {@link FileModifyTool} 的确定性单元测试：验证唯一定位、失败保护和局部替换。
 */
class FileModifyToolTest {

    private final FileModifyTool fileModifyTool = new FileModifyTool();

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private final Path projectRoot = FileWriteTool.projectRoot(appId);

    @AfterEach
    void removeTestProject() {
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    void replacesUniqueOldContentAndReturnsBriefSummary() throws IOException {
        String original = """
                <template>
                  <button class="primary">提交</button>
                </template>
                """;
        Path file = writeFile("src/components/SubmitButton.vue", original);

        String result = fileModifyTool.modifyFile("src/components/SubmitButton.vue",
                "<button class=\"primary\">提交</button>",
                "<button class=\"primary\">立即提交</button>", appId);

        assertTrue(result.startsWith("文件修改成功：src/components/SubmitButton.vue（替换 1 处）"), result);
        assertTrue(result.contains("旧内容摘要："), result);
        assertTrue(result.contains("新内容摘要："), result);
        assertFalse(result.contains(projectRoot.toString()), "工具不能把服务器绝对路径返回给模型");
        String modified = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(modified.contains("<button class=\"primary\">立即提交</button>"), modified);
        assertFalse(modified.contains(">提交<"), "被替换的旧内容不应残留");
    }

    @Test
    void allowsEmptyNewContentToDeleteSnippet() throws IOException {
        Path file = writeFile("src/App.vue", "<template>\n  <p>旧段落</p>\n  <main />\n</template>");

        String result = fileModifyTool.modifyFile("src/App.vue", "  <p>旧段落</p>\n", "", appId);

        assertTrue(result.startsWith("文件修改成功：src/App.vue（替换 1 处）"), result);
        assertEquals("<template>\n  <main />\n</template>", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsMissingOldContentWithoutTouchingFile() throws IOException {
        Path file = writeFile("src/App.vue", "<template><div /></template>");

        String result = fileModifyTool.modifyFile("src/App.vue", "<template><span /></template>",
                "<template><main /></template>", appId);

        assertTrue(result.startsWith("文件修改失败："), result);
        assertTrue(result.contains("旧内容未在文件中找到"), result);
        assertEquals("<template><div /></template>", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAmbiguousOldContentAndReportsOccurrences() throws IOException {
        Path file = writeFile("src/App.vue", "<p>待办</p>\n<p>待办</p>");

        String result = fileModifyTool.modifyFile("src/App.vue", "<p>待办</p>", "<p>完成</p>", appId);

        assertTrue(result.startsWith("文件修改失败："), result);
        assertTrue(result.contains("旧内容在文件中出现 2 次"), result);
        assertTrue(result.contains("唯一"), result);
        assertEquals("<p>待办</p>\n<p>待办</p>", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsBlankOldContentNullNewContentAndIdenticalContent() throws IOException {
        writeFile("src/App.vue", "<template><div /></template>");

        assertTrue(fileModifyTool.modifyFile("src/App.vue", "", "<div></div>", appId)
                .contains("旧内容不能为空"));
        assertTrue(fileModifyTool.modifyFile("src/App.vue", null, "<div></div>", appId)
                .contains("旧内容不能为空"));
        assertTrue(fileModifyTool.modifyFile("src/App.vue", "<div />", null, appId)
                .contains("新内容不能为 null"));
        assertTrue(fileModifyTool.modifyFile("src/App.vue", "<div />", "<div />", appId)
                .contains("新旧内容完全相同"));
    }

    @Test
    void rejectsMissingFileAndInvalidAppId() {
        String missing = fileModifyTool.modifyFile("src/Missing.vue", "a", "b", appId);
        assertTrue(missing.contains("文件不存在"), missing);
        assertTrue(fileModifyTool.modifyFile("src/App.vue", "a", "b", null).contains("应用 ID 无效"));
        assertTrue(fileModifyTool.modifyFile("src/App.vue", "a", "b", 0L).contains("应用 ID 无效"));
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        Path escapedFile = projectRoot.getParent().resolve("modify-escaped-" + appId + ".txt");
        writeFileSafely("src/App.vue", "<div />");
        List<String> invalidPaths = List.of(
                "../modify-escaped-" + appId + ".txt",
                "src/../../modify-escaped-" + appId + ".txt",
                "/etc/passwd",
                "C:\\Windows\\win.ini",
                "C:escaped.txt",
                "."
        );

        for (String invalidPath : invalidPaths) {
            String result = fileModifyTool.modifyFile(invalidPath, "a", "b", appId);
            assertTrue(result.startsWith("文件修改失败："), invalidPath + " 应被拒绝，实际：" + result);
            assertFalse(result.contains(projectRoot.toString()), "失败信息不能泄露绝对路径");
        }
        assertFalse(Files.exists(escapedFile), "路径穿越不能修改工程外文件");
    }

    @Test
    void stripsProjectDirectoryPrefix() throws IOException {
        writeFile("src/App.vue", "old");

        String result = fileModifyTool.modifyFile("./vue_project_" + appId + "\\src\\App.vue",
                "old", "new", appId);

        assertTrue(result.startsWith("文件修改成功：src/App.vue"), result);
        assertEquals("new", Files.readString(projectRoot.resolve("src/App.vue"), StandardCharsets.UTF_8));
    }

    private Path writeFile(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private void writeFileSafely(String relativePath, String content) {
        try {
            writeFile(relativePath, content);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
