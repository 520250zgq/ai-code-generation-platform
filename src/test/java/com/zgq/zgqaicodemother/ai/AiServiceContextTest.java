package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.tools.FileDeleteTool;
import com.zgq.zgqaicodemother.ai.tools.FileDirReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileModifyTool;
import com.zgq.zgqaicodemother.ai.tools.FileReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.config.AiCodeGenTypeRoutingServiceConfig;
import com.zgq.zgqaicodemother.config.AiModelConfig;
import com.zgq.zgqaicodemother.config.ChatMemoryConfig;
import com.zgq.zgqaicodemother.config.ReasoningStreamingChatModelConfig;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 验证带 @MemoryId 的 AiCodeGeneratorService 能装配 chatMemoryProvider，不调用真实模型。
 *
 * <p>同时覆盖服务缓存键：同一 appId + codeGenType 复用代理，不同模式或不同应用必须隔离。</p>
 */
@SpringBootTest(classes = {AiCodeGeneratorServiceFactory.class, AiModelConfig.class,
        AiCodeGenTypeRoutingServiceConfig.class,
        ChatMemoryConfig.class, ReasoningStreamingChatModelConfig.class,
        ToolManager.class, FileWriteTool.class, FileReadTool.class, FileDirReadTool.class,
        FileModifyTool.class, FileDeleteTool.class, TestAiMemoryConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.devtools.restart.enabled=false",
                "langchain4j.open-ai.chat-model.base-url=http://localhost:1",
                "langchain4j.open-ai.chat-model.api-key=test-key",
                "langchain4j.open-ai.chat-model.model-name=test-model",
                "langchain4j.open-ai.streaming-chat-model.base-url=http://localhost:1",
                "langchain4j.open-ai.streaming-chat-model.api-key=test-key",
                "langchain4j.open-ai.streaming-chat-model.model-name=test-model"
        })
class AiServiceContextTest {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Test
    void contextLoadsWithMemoryProviderAndCachesByType() {
        assertNotNull(aiCodeGenTypeRoutingService, "智能路由 AI Service 必须能从 Spring 容器获取");
        AiCodeGeneratorService htmlService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(1L, CodeGenTypeEnum.HTML);
        assertNotNull(htmlService);
        assertSame(htmlService, aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(1L, CodeGenTypeEnum.HTML),
                "同一应用、同一生成模式应复用 AI Service");
        assertNotSame(htmlService,
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(1L, CodeGenTypeEnum.MULTI_FILE),
                "不同生成模式必须使用不同的 AI Service");
        assertNotSame(htmlService,
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(2L, CodeGenTypeEnum.HTML),
                "不同应用必须使用不同的 AI Service");
        assertNotNull(aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(1L, CodeGenTypeEnum.VUE_PROJECT));
    }
}
