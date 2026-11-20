package com.zgq.zgqaicodemother.workflow.tools;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ProjectCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 项目代码拼接工具。
 *
 * <p>质检需要“看完整项目”，但模型只能接收一段文本。本工具负责遍历一次生成的代码目录，
 * 过滤依赖和构建产物，把需要检查的源码按固定顺序拼接成带文件头的文本，同时保留逐文件内容
 * 供静态检查使用。所有读取都有文件数量、单文件长度和总长度上限，避免超大工程撑爆模型上下文。</p>
 *
 * <p>该工具只读取 {@code tmp/code_output} 下的应用目录，并再次校验路径前缀，
 * 防止上下文被篡改后读取服务器任意文件。</p>
 */
@Slf4j
@Component
public class CodeBundleTool {

    /**
     * 不参与质检的目录：依赖、构建产物和 IDE 缓存。
     */
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "node_modules", "dist", "build", "target", ".git", ".idea", ".vscode",
            "coverage", "logs", ".turbo", ".cache");

    /**
     * 参与质检的文件扩展名。只包含文本代码，避免把图片、字体等二进制文件读成乱码。
     */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "html", "htm", "css", "scss", "less", "sass",
            "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue",
            "json", "yml", "yaml", "md", "txt", "svg");

    /**
     * 拼接文本开头的文件树标题。
     */
    private static final String TREE_TITLE = "## 项目文件结构";

    /**
     * 调试日志中最多展示的文件数，避免一次打印整个工程的路径。
     */
    private static final int LOG_FILE_PREVIEW_LIMIT = 10;

    /**
     * 读取并拼接生成目录下的所有代码文件。
     *
     * @param generatedCodeDir 代码生成目录
     * @param config           质检读取上限配置
     * @return 项目代码快照
     */
    public ProjectCode collect(String generatedCodeDir, WorkflowProperties.QualityCheck config) {
        WorkflowProperties.QualityCheck limits = config == null ? new WorkflowProperties.QualityCheck() : config;
        Path root = resolveCodeDir(generatedCodeDir);
        List<Path> candidates = listCodeFiles(root, limits.getMaxFiles());
        boolean truncated = candidates.size() > Math.max(1, limits.getMaxFiles());

        List<ProjectCode.FileEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int skippedFiles = 0;
        int remaining = Math.max(1, limits.getMaxTotalChars());
        boolean textTruncated = truncated;

        for (Path file : candidates) {
            if (entries.size() >= Math.max(1, limits.getMaxFiles())) {
                textTruncated = true;
                break;
            }
            String relativePath = toRelativePath(root, file);
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                skippedFiles++;
                log.debug("读取代码文件失败，已跳过：{}", relativePath, e);
                continue;
            }
            if (StrUtil.isBlank(content) && !isIndexHtml(relativePath)) {
                // 多文件模式下 CSS / JS 允许为空，空文件不占用质检上下文。
                continue;
            }
            int maxFileChars = Math.max(1, limits.getMaxFileChars());
            int allowed = Math.min(maxFileChars, remaining);
            boolean fileTruncated = content.length() > allowed;
            if (fileTruncated) {
                content = StrUtil.maxLength(content, allowed) + "\n...（该文件内容超出长度上限，已截断）";
                textTruncated = true;
            }
            entries.add(ProjectCode.FileEntry.builder()
                    .path(relativePath)
                    .content(content)
                    .truncated(fileTruncated)
                    .build());
            remaining -= content.length();
            if (remaining <= 0) {
                // 总预算已经用完，保留已读取的部分，不再读取后续文件。
                break;
            }
        }

        if (remaining <= 0) {
            textTruncated = true;
            warnings.add("代码总长度超过质检上限，已截断检查");
        }
        if (entries.isEmpty()) {
            warnings.add("代码目录中没有可检查的代码文件");
        }

        text.append(TREE_TITLE).append("\n\n");
        entries.forEach(entry -> text.append("- ").append(entry.getPath()).append('\n'));
        text.append('\n');
        entries.forEach(entry -> text.append("### 文件: ").append(entry.getPath()).append("\n\n")
                .append(entry.getContent()).append("\n\n"));

        ProjectCode code = ProjectCode.builder()
                .files(entries)
                .text(text.toString())
                .truncated(textTruncated)
                .skippedFiles(skippedFiles)
                .warnings(warnings)
                .build();
        if (!entries.isEmpty()) {
            log.info("项目代码拼接完成，文件数={}，字符数={}，truncated={}，skipped={}",
                    entries.size(), code.totalChars(), textTruncated, skippedFiles);
            log.debug("参与质检的文件：{}", entries.stream()
                    .map(ProjectCode.FileEntry::getPath)
                    .limit(LOG_FILE_PREVIEW_LIMIT)
                    .toList());
        }
        return code;
    }

    /**
     * 解析并校验代码目录，必须是 {@code tmp/code_output} 下的应用子目录。
     *
     * @param generatedCodeDir 代码生成目录
     * @return 归一化后的绝对路径
     * @throws IllegalArgumentException 目录为空、不存在或不在允许的根目录下
     */
    public static Path resolveCodeDir(String generatedCodeDir) {
        if (StrUtil.isBlank(generatedCodeDir)) {
            throw new IllegalArgumentException("代码生成目录为空");
        }
        Path root = Path.of(generatedCodeDir).toAbsolutePath().normalize();
        Path outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        if (!root.startsWith(outputRoot) || root.equals(outputRoot)) {
            throw new IllegalArgumentException("代码目录路径不合法");
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("代码目录不存在");
        }
        return root;
    }

    /**
     * 列出允许参与质检的代码文件，按相对路径排序并限制数量。
     */
    private static List<Path> listCodeFiles(Path root, int maxFiles) {
        List<Path> files = new ArrayList<>();
        int limit = Math.max(1, maxFiles) + 1;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && isSkippedDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || !isCodeFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    // 多读一个用于判断是否超过上限，避免遍历整个工程。
                    return files.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("遍历代码目录失败：{}", root, e);
        }
        files.sort(Comparator.comparing(path -> toRelativePath(root, path)));
        return files;
    }

    private static boolean isSkippedDirectory(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith(".") || SKIPPED_DIRECTORIES.contains(name);
    }

    private static boolean isCodeFile(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (fileName.startsWith(".")) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return CODE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isIndexHtml(String relativePath) {
        return "index.html".equalsIgnoreCase(relativePath) || "index.htm".equalsIgnoreCase(relativePath);
    }

    private static String toRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
