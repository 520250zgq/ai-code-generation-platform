package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileDirReadTool} 的确定性单元测试：验证目录树、数量统计和敏感目录过滤。
 */
class FileDirReadToolTest {

    private final FileDirReadTool fileDirReadTool = new FileDirReadTool();

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    private final Path projectRoot = FileWriteTool.projectRoot(appId);

    @AfterEach
    void removeTestProject() {
        FileUtil.del(projectRoot.toFile());
    }

    @Test
    void readsProjectTreeAndSkipsBuildDirectories() throws IOException {
        createProjectFixture();

        String result = fileDirReadTool.readDir("", appId);

        assertTrue(result.startsWith("目录读取成功：.，共 4 个文件，2 个目录\n"), result);
        assertTrue(result.contains("package.json"), result);
        assertTrue(result.contains("src/"), result);
        assertTrue(result.contains("  App.vue"), result);
        assertTrue(result.contains("  components/"), result);
        assertTrue(result.contains("    Header.vue"), result);
        assertTrue(result.contains("    Footer.vue"), result);
        assertFalse(result.contains("node_modules"), "不能展示依赖目录");
        assertFalse(result.contains("dist"), "不能展示构建产物目录");
        assertFalse(result.contains("ignored.js"), "被忽略目录中的文件不能出现在结果中");
        assertFalse(result.contains(projectRoot.toString()), "工具不能返回服务器绝对路径");
    }

    @Test
    void readsSubDirectoryAndSupportsDotRoot() throws IOException {
        createProjectFixture();

        String subDirectory = fileDirReadTool.readDir("src", appId);
        String dotRoot = fileDirReadTool.readDir(".", appId);

        assertTrue(subDirectory.startsWith("目录读取成功：src，共 3 个文件，1 个目录\n"), subDirectory);
        assertTrue(subDirectory.contains("components/"), subDirectory);
        assertTrue(subDirectory.contains("Header.vue"), subDirectory);
        assertFalse(subDirectory.contains("package.json"), "读取子目录时不应出现根目录文件");
        assertTrue(dotRoot.startsWith("目录读取成功：.，共 4 个文件，2 个目录\n"), dotRoot);
    }

    @Test
    void stripsProjectDirectoryPrefix() throws IOException {
        createProjectFixture();

        String result = fileDirReadTool.readDir("vue_project_" + appId + "/src", appId);

        assertTrue(result.startsWith("目录读取成功：src，共 3 个文件，1 个目录\n"), result);
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        for (String invalidPath : new String[]{"../", "src/../../", "/etc", "C:\\Windows", ".."}) {
            String result = fileDirReadTool.readDir(invalidPath, appId);
            assertTrue(result.startsWith("目录读取失败："), invalidPath + " 应被拒绝，实际：" + result);
            assertFalse(result.contains(projectRoot.toString()), "失败信息不能泄露绝对路径");
        }
    }

    @Test
    void reportsMissingOrNonDirectoryPath() throws IOException {
        createProjectFixture();

        String missing = fileDirReadTool.readDir("src/missing", appId);
        String file = fileDirReadTool.readDir("package.json", appId);

        assertTrue(missing.startsWith("目录读取失败："), missing);
        assertTrue(missing.contains("目录不存在"), missing);
        assertTrue(file.startsWith("目录读取失败："), file);
    }

    @Test
    void rejectsInvalidAppId() {
        assertTrue(fileDirReadTool.readDir("", null).contains("应用 ID 无效"));
        assertTrue(fileDirReadTool.readDir("", 0L).contains("应用 ID 无效"));
    }

    private void createProjectFixture() throws IOException {
        writeFile("package.json", "{\"name\":\"demo\"}");
        writeFile("src/App.vue", "<template><div /></template>");
        writeFile("src/components/Header.vue", "<template><header /></template>");
        writeFile("src/components/Footer.vue", "<template><footer /></template>");
        writeFile("node_modules/vite/ignored.js", "ignored");
        writeFile("dist/index.html", "<html></html>");
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
