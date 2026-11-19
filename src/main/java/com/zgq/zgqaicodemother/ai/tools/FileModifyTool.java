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
 * Vue 工程单文件局部修改工具。
 *
 * <p>模型给出文件中一段“旧内容”和“新内容”，工具在文件中查找旧内容并替换。相比整文件重写，
 * 局部修改的输出 token 更少，也不会因为模型复述整文件而意外改动无关代码。</p>
 *
 * <p>为了避免替换错位置，旧内容必须在文件中唯一出现：找不到或出现多次时直接失败，并在结果中
 * 提示模型先读取文件、补充更长的上下文。工具结果只回显新旧内容的短摘要，不把完整文件内容
 * 推送到流式聊天中。</p>
 */
@Slf4j
@Component
public class FileModifyTool extends AbstractFileTool {

    /**
     * 工具名称，必须与 {@link #modifyFile(String, String, String, Long)} 的方法名一致。
     */
    public static final String TOOL_NAME = "modifyFile";

    /**
     * 工具结果中回显的新旧内容最大长度，避免结果占用过多上下文。
     */
    static final int MAX_ECHO_LENGTH = 200;

    private static final String SUCCESS_PREFIX = "文件修改成功：";

    private static final String FAILURE_PREFIX = "文件修改失败：";

    /**
     * 用新内容替换文件中的一段旧内容。
     *
     * @param relativeFilePath 相对于工程根目录的文件路径
     * @param oldContent       文件中需要被替换的原始内容，必须与文件内容完全一致且只出现一次
     * @param newContent       替换后的新内容，允许为空字符串表示删除这段旧内容
     * @param appId            当前应用 id，由框架注入，模型不可见
     * @return 只包含相对路径和新旧内容摘要的执行结果
     */
    @Tool("""
            修改当前应用 Vue 工程中的单个文件：把文件中的旧内容精确替换为新内容。
            修改前必须先使用 readFile 读取文件最新内容，oldContent 必须从文件内容中完整复制，不能省略、不能改写。
            oldContent 必须在文件中唯一出现；如果出现多次，请提供更长、更唯一的上下文，否则工具会失败。
            只替换 oldContent 对应的片段，不要在一次调用中修改多个不相关的位置；多处修改请分多次调用。
            使用相对路径；禁止绝对路径、Windows 盘符和 .. 跳出工程目录。
            返回结果只包含文件相对路径以及新旧内容的短摘要，不会返回完整文件或服务器真实路径。
            """)
    public String modifyFile(
            @P("相对于 Vue 工程根目录的文件路径，使用 / 分隔，例如 src/pages/Home.vue")
            String relativeFilePath,
            @P("文件中需要被替换的原始内容，必须与文件内容完全一致，且在整个文件中只出现一次")
            String oldContent,
            @P("替换后的新内容，允许为空字符串表示删除这段旧内容；不要包含 Markdown 代码围栏")
            String newContent,
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
        if (StrUtil.isEmpty(oldContent)) {
            return failure(normalizedPath + "，旧内容不能为空，请先使用 readFile 查看文件内容");
        }
        if (newContent == null) {
            return failure(normalizedPath + "，新内容不能为 null；如需删除片段请传空字符串");
        }
        if (oldContent.length() > MAX_CONTENT_LENGTH || newContent.length() > MAX_CONTENT_LENGTH) {
            return failure(normalizedPath + "，旧内容或新内容超过单文件上限 " + MAX_CONTENT_LENGTH + " 字符");
        }
        if (oldContent.equals(newContent)) {
            return failure(normalizedPath + "，新旧内容完全相同，无需修改");
        }
        Path target = resolveProjectPath(appId, normalizedPath, false);
        if (target == null) {
            return failure(normalizedPath + "，路径必须位于当前应用工程目录内");
        }
        if (!Files.isRegularFile(target)) {
            return failure(normalizedPath + "，文件不存在，请先使用 readFile 或 writeFile 创建");
        }
        try {
            String fileContent = Files.readString(target, StandardCharsets.UTF_8);
            int firstIndex = fileContent.indexOf(oldContent);
            if (firstIndex < 0) {
                return failure(normalizedPath
                        + "，旧内容未在文件中找到，请先使用 readFile 查看最新内容并完整复制要替换的片段");
            }
            int secondIndex = fileContent.indexOf(oldContent, firstIndex + oldContent.length());
            if (secondIndex >= 0) {
                int occurrence = countOccurrences(fileContent, oldContent);
                return failure(normalizedPath + "，旧内容在文件中出现 " + occurrence
                        + " 次，无法确定替换位置，请补充更长、更唯一的上下文后重试");
            }
            String modifiedContent = fileContent.substring(0, firstIndex)
                    + newContent
                    + fileContent.substring(firstIndex + oldContent.length());
            Files.writeString(target, modifiedContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            log.info("Vue 工程文件修改成功，appId={}，相对路径={}，替换位置={}",
                    appId, normalizedPath, firstIndex);
            return SUCCESS_PREFIX + normalizedPath + "（替换 1 处）\n"
                    + "旧内容摘要：\n" + excerpt(oldContent) + "\n"
                    + "新内容摘要：\n" + excerpt(newContent);
        } catch (IOException | SecurityException e) {
            log.error("修改 Vue 工程文件失败，appId={}，相对路径={}", appId, normalizedPath, e);
            return failure(normalizedPath + "，服务端修改失败，请稍后重试一次");
        }
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String generateToolExecutedResult(ToolExecution toolExecution) {
        return progress("✏️", toolExecution == null ? "" : toolExecution.result());
    }

    /**
     * 统计旧内容出现次数；调用前已确认至少出现两次，这里最多再数一次用于提示。
     */
    private static int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    /**
     * 截取内容摘要，换行保留为可读形式，超长时截断。
     */
    private static String excerpt(String content) {
        if (StrUtil.isEmpty(content)) {
            return "（空）";
        }
        return StrUtil.maxLength(content, MAX_ECHO_LENGTH);
    }

    private static String failure(String detail) {
        return FAILURE_PREFIX + detail;
    }
}
