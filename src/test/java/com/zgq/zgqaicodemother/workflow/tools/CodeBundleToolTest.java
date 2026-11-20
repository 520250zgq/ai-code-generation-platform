package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ProjectCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 项目代码拼接工具测试：过滤规则、排序、长度限制和目录安全。
 */
class CodeBundleToolTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private final CodeBundleTool tool = new CodeBundleTool();

    private final List<Path> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirs.forEach(path -> FileUtil.del(path.toFile()));
    }

    @Test
    void readsCodeFilesAndSkipsDependenciesAndBuildOutput() throws Exception {
        Path dir = createDir();
        Files.writeString(dir.resolve("index.html"), "<html><body>ok</body></html>");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/App.vue"), "<template><div/></template>");
        Files.writeString(dir.resolve("notes.md"), "# 说明");
        Files.writeString(dir.resolve("empty.css"), "  \n");
        Files.createDirectories(dir.resolve("node_modules/pkg"));
        Files.writeString(dir.resolve("node_modules/pkg/index.js"), "dependency");
        Files.createDirectories(dir.resolve("dist"));
        Files.writeString(dir.resolve("dist/bundle.js"), "built");
        Files.createDirectories(dir.resolve(".git"));
        Files.writeString(dir.resolve(".git/config"), "secret");

        ProjectCode code = tool.collect(dir.toString(), new WorkflowProperties.QualityCheck());

        assertEquals(List.of("index.html", "notes.md", "src/App.vue"), code.filePaths());
        assertFalse(code.isTruncated());
        assertTrue(code.getText().contains("## 项目文件结构"), code.getText());
        assertTrue(code.getText().contains("### 文件: src/App.vue"), code.getText());
        assertTrue(code.getText().contains("<template><div/></template>"), code.getText());
        assertFalse(code.getText().contains("dependency"));
        assertFalse(code.getText().contains("built"));
        assertEquals(3, code.getFiles().size());
        assertTrue(code.file("src/app.vue") == null, "路径匹配区分大小写");
    }

    @Test
    void capsFileCountAndTotalChars() throws Exception {
        Path dir = createDir();
        Files.writeString(dir.resolve("index.html"), "<html><body>ok</body></html>");
        Files.writeString(dir.resolve("big.js"), "x".repeat(2_000));
        Files.writeString(dir.resolve("other.css"), "body{}");
        WorkflowProperties.QualityCheck config = new WorkflowProperties.QualityCheck();
        config.setMaxFiles(2);
        config.setMaxFileChars(100);
        config.setMaxTotalChars(500);

        ProjectCode code = tool.collect(dir.toString(), config);

        assertEquals(2, code.getFiles().size());
        assertTrue(code.isTruncated());
        assertTrue(code.getText().contains("已截断"), code.getText());
        assertTrue(code.getFiles().stream().anyMatch(ProjectCode.FileEntry::isTruncated));
        assertTrue(code.totalChars() >= 0);
    }

    @Test
    void blankCodeDirectoryReturnsEmptyWithWarning() throws Exception {
        Path dir = createDir();
        Files.writeString(dir.resolve("empty.css"), "  ");

        ProjectCode code = tool.collect(dir.toString(), new WorkflowProperties.QualityCheck());

        assertTrue(code.isEmpty());
        assertTrue(code.getWarnings().stream().anyMatch(warning -> warning.contains("没有可检查")),
                code.getWarnings().toString());
    }

    @Test
    void keepsBlankIndexHtmlForStaticCheck() throws Exception {
        Path dir = createDir();
        Files.writeString(dir.resolve("index.html"), "  ");

        ProjectCode code = tool.collect(dir.toString(), new WorkflowProperties.QualityCheck());

        assertEquals(List.of("index.html"), code.filePaths());
    }

    @Test
    void rejectsDirectoriesOutsideCodeOutputRoot() {
        assertThrows(IllegalArgumentException.class,
                () -> tool.collect(System.getProperty("java.io.tmpdir"), new WorkflowProperties.QualityCheck()));
        assertThrows(IllegalArgumentException.class, () -> tool.collect(" ", null));
    }

    private Path createDir() throws Exception {
        Path dir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "bundle_" + IDS.incrementAndGet())
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        createdDirs.add(dir);
        return dir;
    }
}
