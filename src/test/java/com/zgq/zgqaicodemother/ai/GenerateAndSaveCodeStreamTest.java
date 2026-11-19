package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.tools.FileDeleteTool;
import com.zgq.zgqaicodemother.ai.tools.FileDirReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileModifyTool;
import com.zgq.zgqaicodemother.ai.tools.FileReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.config.AiModelConfig;
import com.zgq.zgqaicodemother.config.ChatMemoryConfig;
import com.zgq.zgqaicodemother.config.ReasoningStreamingChatModelConfig;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.ai.model.MultiFileCodeResult;
import com.zgq.zgqaicodemother.core.parser.CodeParserExecutor;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 流式生成集成测试：运行时会调用真实模型，并尝试保存生成文件。
 * 使用 -Dai.integration.enabled=true 显式启用。
 */
@EnabledIfSystemProperty(named = "ai.integration.enabled", matches = "true")
@SpringBootTest(classes = {AiCodeGeneratorServiceFactory.class, AiModelConfig.class,
        ChatMemoryConfig.class, ReasoningStreamingChatModelConfig.class,
        ToolManager.class, FileWriteTool.class, FileReadTool.class, FileDirReadTool.class,
        FileModifyTool.class, FileDeleteTool.class,
        AiCodeGeneratorFacade.class, TestAiMemoryConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "langchain4j.open-ai.chat-model.log-requests=false",
                "langchain4j.open-ai.chat-model.log-responses=false",
                "langchain4j.open-ai.streaming-chat-model.log-requests=false",
                "langchain4j.open-ai.streaming-chat-model.log-responses=false"
        })
class GenerateAndSaveCodeStreamTest {

    private static final Path OUTPUT_DIRECTORY = Path.of(System.getProperty("user.dir"), "tmp", "code_output");

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCodeStream() throws IOException {
        long appId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        Path directory = OUTPUT_DIRECTORY.resolve("multi_file_" + appId);
        Assertions.assertFalse(Files.exists(directory), "测试目录已存在，停止运行以避免覆盖文件");
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "任务记录网站", CodeGenTypeEnum.MULTI_FILE, appId);
        // 阻塞等待所有数据收集完成
        List<String> result = codeStream.collectList().block(Duration.ofMinutes(3));
        // 验证结果
        Assertions.assertNotNull(result);
        String completeContent = String.join("", result);
        Assertions.assertFalse(completeContent.isBlank(), "模型响应不能为空");
        MultiFileCodeResult parsed = Assertions.assertInstanceOf(MultiFileCodeResult.class,
                CodeParserExecutor.executeParser(completeContent, CodeGenTypeEnum.MULTI_FILE));
        assertSavedCode(directory, "index.html", parsed.getHtmlCode());
        assertSavedCode(directory, "style.css", parsed.getCssCode());
        assertSavedCode(directory, "script.js", parsed.getJsCode());
    }

    private static void assertSavedCode(Path directory, String filename, String expectedCode) throws IOException {
        Assertions.assertNotNull(expectedCode, filename + " 源码缺失");
        Assertions.assertFalse(expectedCode.isBlank(), filename + " 源码为空");
        Path savedFile = directory.resolve(filename);
        Assertions.assertTrue(Files.isRegularFile(savedFile), filename + " 未保存");
        Assertions.assertEquals(expectedCode, Files.readString(savedFile), filename + " 内容与响应不一致");
    }
}
