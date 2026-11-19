package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vue 工程单文件读取工具。
 *
 * <p>AI 在修改工程前必须先读取目标文件，避免基于猜测生成替换内容。工具只允许读取当前
 * appId 工程目录内的文件，返回相对路径和文件内容，不暴露服务器绝对路径。</p>
 *
 * <p>单次读取内容有上限：超过上限时只返回前若干字符并明确提示被截断，避免异常大文件
 * 占满模型上下文；模型可以继续读取其他文件或使用修改工具做局部替换。</p>
 */
@Slf4j
@Component
public class FileReadTool extends AbstractFileTool {

    /**
     * 工具名称，必须与 {@link #readFile(String, Long)} 的方法名一致。
     */
    public static final String TOOL_NAME = "readFile";

    /**
     * 单次返回给模型的最大字符数。
     */
    static final int MAX_READ_CONTENT_LENGTH = 60_000;

    private static final String SUCCESS_PREFIX = "文件读取成功：";

    private static final String FAILURE_PREFIX = "文件读取失败：";

    /**
     * 读取当前应用 Vue 工程中的单个文件。
     *
     * @param relativeFilePath 相对于工程根目录的文件路径
     * @param appId            当前应用 id，由框架注入，模型不可见
     * @return 包含相对路径和文件内容的执行结果
     */
    @Tool("""
            读取当前应用 Vue 工程中的单个文件内容。
            relativeFilePath 必须是相对于工程根目录的路径，例如 src/App.vue、package.json、src/pages/Home.vue。
            修改任何文件之前，必须先使用本工具读取它的最新内容，不要凭记忆猜测旧内容。
            禁止传入绝对路径、Windows 盘符、以 / 或 \\ 开头的路径，禁止使用 .. 跳出工程目录。
            返回结果包含文件相对路径和文件内容；单次读取内容过长时会被截断并提示。
            """)
    public String readFile(
            @P("相对于 Vue 工程根目录的文件路径，使用 / 分隔，例如 src/App.vue；不能是绝对路径，也不能包含 ..")
            String relativeFilePath,
            @ToolMemoryId Long appId) {
        if (!isValidAppId(appId)) {
            return failure("应用 ID 无效，无法确定文件目录");
        }
        String normalizedPath = normalizeRelativePath(relativeFilePath);
        if (normalizedPath == null) {
            return failure("文件路径不能为空，且必须是工程内的相对路径，例如 src/App.vue");
        }
        normalizedPath = stripProjectDirPrefix(normalizedPath, appId);
        if (StrUtil.isBlank(normalizedPath)) {
            return failure("文件路径不能指向工程根目录，请指定具体文件，例如 src/App.vue");
        }
        Path target = resolveProjectPath(appId, normalizedPath, false);
        if (target == null) {
            return failure(normalizedPath + "，路径必须位于当前应用工程目录内");
        }
        if (!Files.isRegularFile(target)) {
            return failure(normalizedPath + "，文件不存在，请先使用 readDir 查看目录结构");
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            int contentLength = content.length();
            String result = SUCCESS_PREFIX + normalizedPath + "（" + contentLength + " 字符）\n";
            if (StrUtil.isEmpty(content)) {
                return result + "文件内容为空";
            }
            if (contentLength > MAX_READ_CONTENT_LENGTH) {
                // 只截断返回给模型的内容，不修改磁盘文件。
                result += "文件内容过长，以下仅展示前 " + MAX_READ_CONTENT_LENGTH
                        + " 字符，剩余内容已省略：\n" + content.substring(0, MAX_READ_CONTENT_LENGTH);
            } else {
                result += "文件内容：\n" + content;
            }
            return result;
        } catch (IOException | SecurityException e) {
            log.error("读取 Vue 工程文件失败，appId={}，相对路径={}", appId, normalizedPath, e);
            return failure(normalizedPath + "，服务端读取失败，请稍后重试一次");
        }
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String generateToolExecutedResult(ToolExecution toolExecution) {
        return progress("📖", toolExecution == null ? "" : toolExecution.result());
    }

    private static String failure(String detail) {
        return FAILURE_PREFIX + detail;
    }
}
