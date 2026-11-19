package com.zgq.zgqaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Vue 工程单文件删除工具。
 *
 * <p>增量修改时可能需要清理不再使用的组件、页面或样式文件。工具只允许删除当前 appId 工程
 * 目录内的单个文件，拒绝目录和工程外路径，避免模型误删整个工程。</p>
 */
@Slf4j
@Component
public class FileDeleteTool extends AbstractFileTool {

    /**
     * 工具名称，必须与 {@link #deleteFile(String, Long)} 的方法名一致。
     */
    public static final String TOOL_NAME = "deleteFile";

    private static final String SUCCESS_PREFIX = "文件删除成功：";

    private static final String FAILURE_PREFIX = "文件删除失败：";

    /**
     * 删除当前应用 Vue 工程中的单个文件。
     *
     * @param relativeFilePath 相对于工程根目录的文件路径
     * @param appId            当前应用 id，由框架注入，模型不可见
     * @return 只包含相对路径的执行结果
     */
    @Tool("""
            删除当前应用 Vue 工程中的单个文件，用于清理不再需要的组件、页面或配置文件。
            只能删除文件，不能删除目录；删除目录请逐个删除其中的文件。
            使用相对路径，例如 src/components/OldHeader.vue；禁止绝对路径、Windows 盘符和 .. 跳出工程目录。
            如果文件本来就不存在，会视为删除成功，不需要重复调用。
            返回结果只包含文件相对路径，不会暴露服务器的真实存储路径。
            """)
    public String deleteFile(
            @P("相对于 Vue 工程根目录的文件路径，使用 / 分隔，例如 src/components/OldHeader.vue")
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
        if (Files.isDirectory(target)) {
            return failure(normalizedPath + "，该路径是目录，本工具只能删除单个文件");
        }
        try {
            if (!Files.deleteIfExists(target)) {
                // 文件已经不存在时视为目标状态已达成，避免模型反复尝试删除同一个文件。
                return SUCCESS_PREFIX + normalizedPath + "（文件原本不存在）";
            }
            log.info("Vue 工程文件删除成功，appId={}，相对路径={}", appId, normalizedPath);
            return SUCCESS_PREFIX + normalizedPath;
        } catch (IOException | SecurityException e) {
            log.error("删除 Vue 工程文件失败，appId={}，相对路径={}", appId, normalizedPath, e);
            return failure(normalizedPath + "，服务端删除失败，请稍后重试一次");
        }
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String generateToolExecutedResult(ToolExecution toolExecution) {
        return progress("🗑️", toolExecution == null ? "" : toolExecution.result());
    }

    private static String failure(String detail) {
        return FAILURE_PREFIX + detail;
    }
}
