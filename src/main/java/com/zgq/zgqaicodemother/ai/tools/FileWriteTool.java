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
import java.nio.file.StandardOpenOption;

/**
 * Vue 工程文件写入工具，用于创建新文件或在必要时整文件覆盖。
 *
 * <p>AI 通过工具调用逐个创建工程文件，而不是把多个文件拼成一段文本再让后端解析。
 * 工具通过 {@link ToolMemoryId} 取得当前对话的 appId，并据此把文件写入
 * {@code tmp/code_output/vue_project_{appId}}，从而保证不同应用的文件互不覆盖。</p>
 *
 * <p>安全约束：只接受工程内的相对路径，拒绝绝对路径、盘符和 {@code ..} 穿越；
 * 返回给 AI（进而可能展示给用户）的内容只包含相对路径，绝不返回服务器绝对路径。
 * 公共路径校验由 {@link AbstractFileTool} 统一实现。</p>
 */
@Slf4j
@Component
public class FileWriteTool extends AbstractFileTool {

    /**
     * 工具名称。提示词和流式进度展示都依赖这个名称，集中定义避免多处硬编码。
     */
    public static final String TOOL_NAME = "writeFile";

    private static final String SUCCESS_PREFIX = "文件写入成功：";

    private static final String FAILURE_PREFIX = "文件写入失败：";

    /**
     * 写入一份完整的工程文件。
     *
     * <p>方法参数上的 {@link ToolMemoryId} 不会出现在工具 schema 中，也不会由模型生成，
     * 而是由 LangChain4j 在工具执行时注入当前对话的 memoryId（本项目中即 appId）。</p>
     *
     * @param relativeFilePath 相对于 Vue 工程根目录的文件路径
     * @param content          文件的完整内容
     * @param appId            当前应用的 id，由框架注入，模型不可见
     * @return 只包含相对路径的执行结果，供模型判断是否成功
     */
    @Tool("""
            将一份完整文件内容写入当前应用的 Vue 工程目录，用于创建新文件，或在必要时整文件覆盖。
            修改已有文件时优先使用 modifyFile，只有需要整体重写、或 modifyFile 无法唯一定位旧内容时才使用本工具。
            relativeFilePath 必须是相对于工程根目录的路径，例如 package.json、vite.config.js、src/App.vue、src/router/index.js。
            禁止传入绝对路径、Windows 盘符、以 / 或 \\ 开头的路径，禁止使用 .. 跳出工程目录。
            每个文件只调用一次，content 必须是可直接运行的完整源码，不要使用 Markdown 代码围栏，也不要只写代码片段。
            返回结果只包含文件相对路径，不会暴露服务器的真实存储路径。
            """)
    public String writeFile(
            @P("相对于 Vue 工程根目录的文件路径，使用 / 分隔，例如 src/components/Header.vue；不能是绝对路径，也不能包含 ..")
            String relativeFilePath,
            @P("要写入的完整文件内容，不要包含 Markdown 代码围栏，也不要只写片段")
            String content,
            @ToolMemoryId Long appId) {
        if (!isValidAppId(appId)) {
            return failure("应用 ID 无效，无法确定文件保存目录");
        }
        String normalizedPath = normalizeRelativePath(relativeFilePath);
        if (normalizedPath == null) {
            return failure("文件路径不能为空，且必须是工程内的相对路径，例如 src/App.vue");
        }
        normalizedPath = stripProjectDirPrefix(normalizedPath, appId);
        if (StrUtil.isBlank(normalizedPath)) {
            return failure("文件路径不能指向工程根目录本身，请指定具体文件，例如 src/App.vue");
        }
        if (StrUtil.isBlank(content)) {
            return failure(normalizedPath + "，文件内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            return failure(normalizedPath + "，文件内容超过单文件上限 " + MAX_CONTENT_LENGTH + " 字符");
        }

        Path target = resolveProjectPath(appId, normalizedPath, false);
        if (target == null) {
            return failure(normalizedPath + "，路径必须位于当前应用工程目录内");
        }
        // 重复写同一个文件是工具调用死循环最常见的形态：先由保护器判定是否该拦截，
        // 再判断“内容是否本来就一致”，避免同一份内容被反复落盘。
        ToolCallDecision decision = checkToolCall(appId, TOOL_NAME, normalizedPath);
        if (!decision.allowed()) {
            return failure(decision.hint());
        }
        String existingContent = readExistingContent(target);
        if (existingContent != null && existingContent.equals(content)) {
            log.info("Vue 工程文件内容未变化，跳过重复写入，appId={}，相对路径={}", appId, normalizedPath);
            return SUCCESS_PREFIX + normalizedPath + "（内容与现有文件一致，已跳过重复写入）";
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            log.info("Vue 工程文件写入成功，appId={}，相对路径={}，绝对路径={}", appId, normalizedPath, target);
            // 只返回相对路径，避免模型把服务器存储路径复述给用户。
            return SUCCESS_PREFIX + normalizedPath;
        } catch (IOException | SecurityException e) {
            // 日志保留绝对路径便于排查，但返回给模型的内容不能包含服务器路径。
            log.error("Vue 工程文件写入失败，appId={}，相对路径={}", appId, normalizedPath, e);
            return failure(normalizedPath + "，服务端写入失败，请检查路径后重试一次");
        }
    }

    /**
     * 读取目标文件现有内容，用于幂等判断。
     *
     * <p>读取失败（不存在、权限问题、编码异常）时返回 null，表示“无法确认内容一致”，
     * 后续仍然执行写入，不能因为幂等优化而丢掉正常的写入能力。</p>
     *
     * @param target 工程内的目标文件
     * @return 现有内容；文件不存在或读取失败返回 null
     */
    private static String readExistingContent(Path target) {
        try {
            if (!Files.isRegularFile(target)) {
                return null;
            }
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String generateToolExecutedResult(ToolExecution toolExecution) {
        return progress("📄", toolExecution == null ? "" : toolExecution.result());
    }

    private static String failure(String detail) {
        return FAILURE_PREFIX + detail;
    }
}
