package com.zgq.zgqaicodemother.ai.tools;

import com.zgq.zgqaicodemother.constant.AppConstant;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolManager} 的确定性单元测试：验证策略注册、结果转换和未知工具兜底。
 */
class ToolManagerTest {

    private final ToolManager toolManager = new ToolManager(List.of(
            new FileWriteTool(), new FileReadTool(), new FileDirReadTool(),
            new FileModifyTool(), new FileDeleteTool()));

    @Test
    void registersEveryFileToolByName() {
        Set<String> names = Arrays.stream(toolManager.getTools())
                .map(BaseTool::getToolName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(FileWriteTool.TOOL_NAME, FileReadTool.TOOL_NAME, FileDirReadTool.TOOL_NAME,
                FileModifyTool.TOOL_NAME, FileDeleteTool.TOOL_NAME), names);
        assertEquals(5, toolManager.getTools().length);
        assertNotNull(toolManager.getTool(FileModifyTool.TOOL_NAME));
        assertNull(toolManager.getTool("notExists"));
        assertNull(toolManager.getTool(" "));
        assertEquals("deleteFile、modifyFile、readDir、readFile、writeFile", toolManager.getToolNames());
    }

    @Test
    void returnsDefensiveCopyOfTools() {
        BaseTool[] first = toolManager.getTools();
        first[0] = null;

        BaseTool[] second = toolManager.getTools();

        assertNotNull(second[0], "调用方修改返回数组不能影响管理器内部状态");
    }

    @Test
    void convertsWriteToolResultToSingleLineProgress() {
        String progress = toolManager.generateToolExecutedResult(toolExecution(FileWriteTool.TOOL_NAME,
                "文件写入成功：src/App.vue\n不应该展示的第二行"));

        assertEquals("\n\n> 📄 文件写入成功：src/App.vue\n\n", progress);
    }

    @Test
    void hidesFileContentForReadAndModifyTools() {
        String readProgress = toolManager.generateToolExecutedResult(toolExecution(FileReadTool.TOOL_NAME,
                "文件读取成功：src/App.vue（100 字符）\n文件内容：\n<template>secret</template>"));
        String modifyProgress = toolManager.generateToolExecutedResult(toolExecution(FileModifyTool.TOOL_NAME,
                "文件修改成功：src/App.vue（替换 1 处）\n旧内容摘要：\nold\n新内容摘要：\nnew"));

        assertEquals("\n\n> 📖 文件读取成功：src/App.vue（100 字符）\n\n", readProgress);
        assertFalse(readProgress.contains("secret"), "流式进度不能包含文件内容");
        assertEquals("\n\n> ✏️ 文件修改成功：src/App.vue（替换 1 处）\n\n", modifyProgress);
        assertFalse(modifyProgress.contains("旧内容摘要"), "流式进度只保留首行摘要");
    }

    @Test
    void usesFallbackProgressForUnknownToolAndSanitizesAbsolutePath() {
        String unknown = toolManager.generateToolExecutedResult(toolExecution("unknownTool", "第一行\n第二行"));
        String absolute = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_1/src/App.vue";
        String sanitized = toolManager.generateToolExecutedResult(
                toolExecution(FileWriteTool.TOOL_NAME, "文件写入成功：" + absolute));

        assertEquals("\n\n> 🔧 unknownTool：第一行\n\n", unknown);
        assertTrue(sanitized.startsWith("\n\n> 📄 文件写入成功："), sanitized);
        assertFalse(sanitized.contains("code_output"), "不能通过 SSE 暴露服务器存储目录");
        assertTrue(sanitized.contains("src/App.vue"), "仍应保留可读的相对路径");
    }

    @Test
    void returnsEmptyForNullExecution() {
        assertEquals("", toolManager.generateToolExecutedResult(null));
    }

    @Test
    void rejectsDuplicateOrBlankToolNames() {
        BaseTool duplicate = new BaseTool() {
            @Override
            public String getToolName() {
                return FileWriteTool.TOOL_NAME;
            }

            @Override
            public String generateToolExecutedResult(ToolExecution toolExecution) {
                return "";
            }
        };
        BaseTool blank = new BaseTool() {
            @Override
            public String getToolName() {
                return " ";
            }

            @Override
            public String generateToolExecutedResult(ToolExecution toolExecution) {
                return "";
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new ToolManager(List.of(new FileWriteTool(), duplicate)));
        assertThrows(IllegalStateException.class, () -> new ToolManager(List.of(blank)));
        assertEquals(0, new ToolManager(null).getTools().length);
    }

    private static ToolExecution toolExecution(String toolName, String result) {
        return ToolExecution.builder()
                .request(ToolExecutionRequest.builder()
                        .id("call")
                        .name(toolName)
                        .arguments("{}")
                        .build())
                .result(result)
                .build();
    }
}
