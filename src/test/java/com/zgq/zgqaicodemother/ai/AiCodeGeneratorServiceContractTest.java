package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.tools.BaseTool;
import com.zgq.zgqaicodemother.ai.tools.FileDeleteTool;
import com.zgq.zgqaicodemother.ai.tools.FileDirReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileModifyTool;
import com.zgq.zgqaicodemother.ai.tools.FileReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过反射锁定 LangChain4j 注解契约，防止后续改动丢失 @MemoryId、工具描述或提示词资源。
 */
class AiCodeGeneratorServiceContractTest {

    @Test
    void vueStreamMethodDeclaresMemoryIdUserMessageAndPromptResource() throws NoSuchMethodException {
        Method method = AiCodeGeneratorService.class.getMethod(
                "generateVueProjectCodeStream", Long.class, String.class);

        assertEquals(TokenStream.class, method.getReturnType());
        Parameter[] parameters = method.getParameters();
        assertNotNull(parameters[0].getAnnotation(MemoryId.class), "@MemoryId 必须加在 appId 上");
        assertNotNull(parameters[1].getAnnotation(UserMessage.class));
        SystemMessage systemMessage = method.getAnnotation(SystemMessage.class);
        assertNotNull(systemMessage);
        assertEquals("prompt/codegen-vue-project-system-prompt.txt", systemMessage.fromResource());
    }

    @Test
    void everyDialogueMethodDeclaresMemoryId() {
        for (Method method : AiCodeGeneratorService.class.getDeclaredMethods()) {
            assertTrue(Arrays.stream(method.getParameters())
                            .anyMatch(parameter -> parameter.isAnnotationPresent(MemoryId.class)),
                    method.getName() + " 必须声明 @MemoryId，工具调用才能拿到 appId");
        }
    }

    @Test
    void writeFileToolDeclaresDescriptionsAndHiddenMemoryId() throws NoSuchMethodException {
        Method method = FileWriteTool.class.getMethod("writeFile", String.class, String.class, Long.class);

        Tool tool = method.getAnnotation(Tool.class);
        assertNotNull(tool, "writeFile 必须标注 @Tool");
        assertEquals(1, tool.value().length);
        assertFalse(tool.value()[0].isBlank(), "工具描述不能为空");
        assertTrue(tool.value()[0].contains("相对路径"), "工具描述必须提醒模型使用相对路径");
        assertTrue(tool.value()[0].contains("绝对路径"), "工具描述必须禁止绝对路径");

        Parameter[] parameters = method.getParameters();
        P pathDescription = parameters[0].getAnnotation(P.class);
        P contentDescription = parameters[1].getAnnotation(P.class);
        assertNotNull(pathDescription);
        assertNotNull(contentDescription);
        assertFalse(pathDescription.value().isBlank());
        assertFalse(contentDescription.value().isBlank());
        assertTrue(pathDescription.value().contains("相对"), "路径参数描述必须说明相对路径");
        assertTrue(parameters[2].isAnnotationPresent(ToolMemoryId.class),
                "appId 必须使用 @ToolMemoryId 由框架注入");
        assertNull(parameters[2].getAnnotation(P.class), "@ToolMemoryId 参数不应暴露给模型");

        // 工具 schema 只应包含两个业务参数，appId 不能出现在模型可见的参数列表中。
        ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(method);
        assertEquals(FileWriteTool.TOOL_NAME, specification.name());
        Map<String, ?> properties = specification.parameters().properties();
        assertEquals(2, properties.size());
        assertFalse(properties.containsKey("appId"));
        assertTrue(properties.containsKey("relativeFilePath"));
        assertTrue(properties.containsKey("content"));
    }

    @Test
    void everyFileToolMatchesItsToolNameAndHidesMemoryId() throws Exception {
        assertToolContract(FileWriteTool.class, FileWriteTool.TOOL_NAME,
                Set.of("relativeFilePath", "content"));
        assertToolContract(FileReadTool.class, FileReadTool.TOOL_NAME,
                Set.of("relativeFilePath"));
        assertToolContract(FileDirReadTool.class, FileDirReadTool.TOOL_NAME,
                Set.of("relativeDirPath"));
        assertToolContract(FileModifyTool.class, FileModifyTool.TOOL_NAME,
                Set.of("relativeFilePath", "oldContent", "newContent"));
        assertToolContract(FileDeleteTool.class, FileDeleteTool.TOOL_NAME,
                Set.of("relativeFilePath"));
    }

    /**
     * 锁定单个工具的注解契约：名称、相对路径提示、@ToolMemoryId 隐藏和模型可见参数集合。
     */
    private static void assertToolContract(Class<?> toolClass, String toolName, Set<String> businessParams)
            throws Exception {
        BaseTool baseTool = (BaseTool) toolClass.getDeclaredConstructor().newInstance();
        assertEquals(toolName, baseTool.getToolName(), toolClass.getSimpleName() + " 的工具名必须与方法名一致");

        Method toolMethod = Arrays.stream(toolClass.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(toolClass.getSimpleName() + " 缺少 @Tool 方法"));
        assertEquals(toolName, toolMethod.getName(), "@Tool 方法名必须与工具名一致");

        Tool tool = toolMethod.getAnnotation(Tool.class);
        assertNotNull(tool);
        assertFalse(tool.value()[0].isBlank(), toolName + " 的工具描述不能为空");
        assertTrue(tool.value()[0].contains("相对路径"), toolName + " 必须提醒模型使用相对路径");

        Parameter[] parameters = toolMethod.getParameters();
        Parameter memoryIdParameter = parameters[parameters.length - 1];
        assertTrue(memoryIdParameter.isAnnotationPresent(ToolMemoryId.class),
                toolName + " 的 appId 必须使用 @ToolMemoryId 注入");
        assertNull(memoryIdParameter.getAnnotation(P.class),
                toolName + " 的 @ToolMemoryId 参数不应暴露给模型");

        ToolSpecification specification = ToolSpecifications.toolSpecificationFrom(toolMethod);
        assertEquals(toolName, specification.name());
        assertEquals(businessParams, specification.parameters().properties().keySet(),
                toolName + " 的模型可见参数必须与设计一致");
        assertFalse(specification.parameters().properties().containsKey("appId"),
                toolName + " 不能把 appId 暴露给模型");
    }
}
