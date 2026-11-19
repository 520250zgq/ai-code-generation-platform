package com.zgq.zgqaicodemother.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元测试代码下载过滤规则和 ZIP 条目生成，不依赖数据库或 Web 容器。
 */
class CodeDownloadUtilsTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {
            "index.html",
            "src/App.vue",
            "src/main.js",
            "src/builder/BuildService.java",
            "src/distance.js",
            "src/my.environment.js",
            "scripts/build.js",
            "package.json",
            "package-lock.json",
            ".gitignore",
            "public/logo.png",
            "docs/environment.md"
    })
    void keepsSourceFilesAndKeywordLikeNames(String relativePath) {
        assertTrue(CodeDownloadUtils.isDownloadable(Path.of(relativePath)), relativePath);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "node_modules/vue/index.js",
            "src/node_modules/local/index.js",
            "dist/index.html",
            "build/app.js",
            "target/classes/App.class",
            "coverage/lcov.info",
            ".git/config",
            ".idea/workspace.xml",
            ".vscode/settings.json",
            ".next/server/page.js",
            ".env",
            ".env.local",
            ".env.example",
            "config/.env.production",
            "config/app.env",
            "config/prod.env.local",
            ".DS_Store",
            "src/.DS_Store",
            "Thumbs.db",
            ".npmrc",
            ".git-credentials",
            "npm-debug.log",
            "logs/app.log",
            "certs/server.pem",
            "certs/server.key"
    })
    void rejectsDependenciesBuildOutputsAndSensitiveFiles(String relativePath) {
        assertFalse(CodeDownloadUtils.isDownloadable(Path.of(relativePath)), relativePath);
    }

    @Test
    void rejectsAbsoluteEmptyAndTraversalPaths() {
        assertFalse(CodeDownloadUtils.isDownloadable(tempDir.resolve("index.html")));
        assertFalse(CodeDownloadUtils.isDownloadable(Path.of("")));
        assertFalse(CodeDownloadUtils.isDownloadable(Path.of("..", "secret.txt")));
        assertFalse(CodeDownloadUtils.isDownloadable(Path.of("src", "..", "..", "secret.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> CodeDownloadUtils.toZipEntryName(Path.of("..", "secret.txt")));
        assertThrows(IllegalArgumentException.class,
                () -> CodeDownloadUtils.toZipEntryName(Path.of("")));
        assertThrows(IllegalArgumentException.class,
                () -> CodeDownloadUtils.toZipEntryName(tempDir.resolve("index.html")));
    }

    @Test
    void buildsSafeDownloadFileNames() {
        assertEquals("我的作品_123.zip", CodeDownloadUtils.buildDownloadFileName("  我的作品  ", 123));
        assertEquals("app_1.zip", CodeDownloadUtils.buildDownloadFileName(null, 1));
        assertEquals("app_1.zip", CodeDownloadUtils.buildDownloadFileName("   ", 1));
        assertEquals("app_1.zip", CodeDownloadUtils.buildDownloadFileName("...", 1));

        String unsafe = CodeDownloadUtils.buildDownloadFileName("a\"b\r\nc/d:e", 9);
        assertFalse(unsafe.contains("\""), unsafe);
        assertFalse(unsafe.contains("\r"), unsafe);
        assertFalse(unsafe.contains("\n"), unsafe);
        assertFalse(unsafe.contains("/"), unsafe);
        assertFalse(unsafe.contains(":"), unsafe);
        assertTrue(unsafe.endsWith("_9.zip"), unsafe);

        String longName = CodeDownloadUtils.buildDownloadFileName("x".repeat(120), 7);
        assertEquals(50 + "_7.zip".length(), longName.length());
        assertTrue(longName.startsWith("x".repeat(50)));
    }

    @Test
    void collectsOnlyDownloadableFilesAndWritesRelativeZipEntries() throws IOException {
        writeFile("index.html", "<h1>app</h1>");
        writeFile("src/main.js", "console.log('app')");
        writeFile("src/builder/BuildService.java", "class BuildService {}");
        writeFile("src/组件/按钮.vue", "<template><button>按钮</button></template>");
        writeFile("package.json", "{}");
        writeFile("node_modules/vue/index.js", "dependency");
        writeFile("dist/index.html", "built");
        writeFile("build/app.js", "built");
        writeFile(".env", "SECRET=1");
        writeFile(".env.local", "SECRET=2");
        writeFile(".DS_Store", "metadata");
        writeFile("logs/app.log", "log");
        writeFile(".git/config", "git");

        List<Path> files = CodeDownloadUtils.collectDownloadableFiles(tempDir);
        List<String> entryNames = files.stream()
                .map(file -> CodeDownloadUtils.toZipEntryName(tempDir.relativize(file)))
                .toList();
        assertEquals(List.of(
                "index.html",
                "package.json",
                "src/builder/BuildService.java",
                "src/main.js",
                "src/组件/按钮.vue"), entryNames);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodeDownloadUtils.writeZip(tempDir, files, output);
        Map<String, String> entries = readZip(output.toByteArray());
        assertEquals(entryNames, entries.keySet().stream().toList());
        assertEquals("<h1>app</h1>", entries.get("index.html"));
        assertEquals("console.log('app')", entries.get("src/main.js"));
        assertEquals("<template><button>按钮</button></template>", entries.get("src/组件/按钮.vue"));
    }

    @Test
    void missingSourceDirectoryProducesNoFiles() throws IOException {
        assertTrue(CodeDownloadUtils.collectDownloadableFiles(tempDir.resolve("missing")).isEmpty());
        assertTrue(CodeDownloadUtils.collectDownloadableFiles(null).isEmpty());
    }

    private void writeFile(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Map<String, String> readZip(byte[] zipBytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zipInput = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zipInput.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
