package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Vue 工程目录结构读取工具。
 *
 * <p>AI 在增量修改前需要先了解工程结构，再决定读取和修改哪些文件。工具递归列出指定目录下的
 * 文件树，自动跳过依赖、构建产物和版本控制目录，避免一次输出过多无关内容。</p>
 *
 * <p>结果只包含相对路径；目录带 {@code /} 后缀。为控制模型上下文，限制递归深度、条目数量和
 * 单个目录的展示范围，超出时明确提示模型缩小目录范围。</p>
 */
@Slf4j
@Component
public class FileDirReadTool extends AbstractFileTool {

    /**
     * 工具名称，必须与 {@link #readDir(String, Long)} 的方法名一致。
     */
    public static final String TOOL_NAME = "readDir";

    /**
     * 最大递归深度（相对于请求的目录）。
     */
    static final int MAX_DEPTH = 8;

    /**
     * 单次最多展示的文件和目录条目数。
     */
    static final int MAX_ENTRIES = 500;

    /**
     * 不参与展示的依赖、构建和版本控制目录。
     */
    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "node_modules", "dist", ".git", ".idea", ".vscode", "target", "coverage", ".DS_Store");

    private static final String SUCCESS_PREFIX = "目录读取成功：";

    private static final String FAILURE_PREFIX = "目录读取失败：";

    /**
     * 递归读取当前应用 Vue 工程中的目录结构。
     *
     * @param relativeDirPath 相对于工程根目录的目录路径，空或 {@code .} 表示根目录
     * @param appId           当前应用 id，由框架注入，模型不可见
     * @return 目录路径和文件列表
     */
    @Tool("""
            递归读取当前应用 Vue 工程中的目录结构（只接受相对路径），用于了解项目组织方式。
            relativeDirPath 是相对于工程根目录的目录路径，例如 src、src/components；传空字符串或 . 表示工程根目录。
            返回结果中目录以 / 结尾，并附带文件数量统计；node_modules、dist 等构建目录会被自动忽略。
            修改前建议先调用本工具了解工程结构，再使用 readFile 查看具体文件。
            禁止传入绝对路径、Windows 盘符或包含 .. 的路径。
            """)
    public String readDir(
            @P("相对于工程根目录的目录路径，例如 src 或 src/components；空字符串或 . 表示工程根目录")
            String relativeDirPath,
            @ToolMemoryId Long appId) {
        if (!isValidAppId(appId)) {
            return failure("应用 ID 无效，无法确定工程目录");
        }
        String normalizedDir = normalizeRelativeDirectory(relativeDirPath);
        if (normalizedDir == null) {
            return failure("目录路径必须是工程内的相对路径，例如 src 或 src/components");
        }
        normalizedDir = stripProjectDirPrefix(normalizedDir, appId);
        Path target = resolveProjectPath(appId, normalizedDir, true);
        if (target == null) {
            return failure("目录路径必须位于当前应用工程目录内");
        }
        if (!Files.isDirectory(target)) {
            return failure(displayPath(normalizedDir) + "，目录不存在，请先使用 readDir 查看工程根目录");
        }
        String rootLabel = StrUtil.blankToDefault(normalizedDir, ".");
        TreeBuilder treeBuilder = new TreeBuilder();
        try {
            treeBuilder.appendDirectory(target, 0);
        } catch (IOException | SecurityException e) {
            log.error("读取 Vue 工程目录失败，appId={}，相对路径={}", appId, rootLabel, e);
            return failure(rootLabel + "，服务端读取失败，请稍后重试一次");
        }
        StringBuilder result = new StringBuilder();
        result.append(SUCCESS_PREFIX).append(rootLabel)
                .append("，共 ").append(treeBuilder.fileCount).append(" 个文件，")
                .append(treeBuilder.directoryCount).append(" 个目录\n");
        if (treeBuilder.lines.isEmpty()) {
            result.append("（空目录）");
        } else {
            result.append(String.join("\n", treeBuilder.lines));
        }
        if (treeBuilder.truncated) {
            result.append("\n…（已达到展示上限，请指定更具体的子目录继续查看）");
        }
        return result.toString();
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String generateToolExecutedResult(ToolExecution toolExecution) {
        return progress("📂", toolExecution == null ? "" : toolExecution.result());
    }

    private static String displayPath(String normalizedDir) {
        return StrUtil.blankToDefault(normalizedDir, ".");
    }

    private static String failure(String detail) {
        return FAILURE_PREFIX + detail;
    }

    /**
     * 目录树构建器，集中维护深度、条目数和缩进，避免递归方法参数过多。
     */
    private static final class TreeBuilder {

        private final List<String> lines = new ArrayList<>();
        private int entryCount;
        private int fileCount;
        private int directoryCount;
        private boolean truncated;

        /**
         * 追加一个目录及其子项。
         *
         * @param directory 目录路径
         * @param depth     当前深度，0 表示请求的根目录
         */
        private void appendDirectory(Path directory, int depth) throws IOException {
            if (truncated) {
                return;
            }
            if (depth > MAX_DEPTH) {
                lines.add(indent(depth) + "…（目录层级过深，已省略）");
                truncated = true;
                return;
            }
            List<Path> children = listChildren(directory);
            for (Path child : children) {
                if (entryCount >= MAX_ENTRIES) {
                    truncated = true;
                    return;
                }
                String name = child.getFileName().toString();
                if (EXCLUDED_NAMES.contains(name)) {
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    entryCount++;
                    directoryCount++;
                    lines.add(indent(depth) + name + "/");
                    appendDirectory(child, depth + 1);
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    entryCount++;
                    fileCount++;
                    lines.add(indent(depth) + name);
                }
            }
        }

        /**
         * 按名称排序，保证同一目录多次读取时输出稳定，便于模型比较前后差异。
         */
        private static List<Path> listChildren(Path directory) throws IOException {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                stream.forEach(children::add);
            }
            children.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return children;
        }

        private static String indent(int depth) {
            return "  ".repeat(Math.max(0, depth));
        }
    }
}
