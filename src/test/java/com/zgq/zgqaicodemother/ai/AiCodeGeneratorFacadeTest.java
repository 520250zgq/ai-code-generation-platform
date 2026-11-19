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
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@EnabledIfSystemProperty(named = "ai.integration.enabled", matches = "true")
@SpringBootTest(classes = {AiCodeGeneratorServiceFactory.class, AiModelConfig.class,
        ChatMemoryConfig.class, ReasoningStreamingChatModelConfig.class,
        ToolManager.class, FileWriteTool.class, FileReadTool.class, FileDirReadTool.class,
        FileModifyTool.class, FileDeleteTool.class,
        AiCodeGeneratorFacade.class, TestAiMemoryConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "langchain4j.open-ai.chat-model.max-retries=0",
                "langchain4j.open-ai.chat-model.log-requests=false",
                "langchain4j.open-ai.chat-model.log-responses=false",
                "langchain4j.open-ai.streaming-chat-model.log-requests=false",
                "langchain4j.open-ai.streaming-chat-model.log-responses=false"
        })
class AiCodeGeneratorFacadeTest {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCode() {
        long appId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        Path directory = Path.of(System.getProperty("user.dir"), "tmp", "code_output", "multi_file_" + appId);
        Assertions.assertFalse(Files.exists(directory), "测试目录已存在，停止运行以避免覆盖文件");
        File file = aiCodeGeneratorFacade.generateAndSaveCode("任务记录网站", CodeGenTypeEnum.MULTI_FILE, appId);
        Assertions.assertNotNull(file);
        Assertions.assertEquals(directory, file.toPath());
        Assertions.assertTrue(Files.isRegularFile(directory.resolve("index.html")));
    }
}
