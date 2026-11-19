package com.zgq.zgqaicodemother.ai;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.ai.memory.ConversationMemoryCache;
import com.zgq.zgqaicodemother.ai.tools.FileDeleteTool;
import com.zgq.zgqaicodemother.ai.tools.FileDirReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileModifyTool;
import com.zgq.zgqaicodemother.ai.tools.FileReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI Service 工厂的确定性单元测试：模型和记忆存储都使用 Mockito，不发起真实调用。
 */
@ExtendWith(MockitoExtension.class)
class AiCodeGeneratorServiceFactoryTest {

    private static final long HTML_APP_ID = 101L;

    /**
     * 使用随机 appId，避免测试清理目录时误删真实应用的生成文件。
     */
    private final long vueAppId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

    @Mock
    private ChatModel chatModel;

    @Mock
    private StreamingChatModel openAiStreamingChatModel;

    @Mock
    private StreamingChatModel reasoningStreamingChatModel;

    @Mock
    private ConversationMemoryCache conversationMemoryCache;

    private AiCodeGeneratorServiceFactory factory;

    @BeforeEach
    void setUp() {
        ToolManager toolManager = new ToolManager(List.of(
                new FileWriteTool(), new FileReadTool(), new FileDirReadTool(),
                new FileModifyTool(), new FileDeleteTool()));
        factory = new AiCodeGeneratorServiceFactory(chatModel, openAiStreamingChatModel,
                reasoningStreamingChatModel, conversationMemoryCache, toolManager);
    }

    @AfterEach
    void removeVueTestProject() {
        FileUtil.del(FileWriteTool.projectRoot(vueAppId).toFile());
    }

    @Test
    void cachesServicesByAppIdAndCodeGenType() {
        AiCodeGeneratorService html = factory.getAiCodeGeneratorService(HTML_APP_ID, CodeGenTypeEnum.HTML);

        assertSame(html, factory.getAiCodeGeneratorService(HTML_APP_ID, CodeGenTypeEnum.HTML),
                "同一 appId + 类型必须复用同一代理");
        assertNotSame(html, factory.getAiCodeGeneratorService(HTML_APP_ID, CodeGenTypeEnum.MULTI_FILE),
                "不同生成模式必须使用不同代理");
        assertNotSame(html, factory.getAiCodeGeneratorService(HTML_APP_ID + 1, CodeGenTypeEnum.HTML),
                "不同应用必须使用不同代理");
        assertEquals("101:html", AiCodeGeneratorServiceFactory.buildCacheKey(HTML_APP_ID, CodeGenTypeEnum.HTML));
        assertEquals("101:multi_file",
                AiCodeGeneratorServiceFactory.buildCacheKey(HTML_APP_ID, CodeGenTypeEnum.MULTI_FILE));
        assertEquals("101:vue_project",
                AiCodeGeneratorServiceFactory.buildCacheKey(HTML_APP_ID, CodeGenTypeEnum.VUE_PROJECT));
    }

    @Test
    void workflowServiceIsNotCachedAndDoesNotTouchConversationMemory() {
        AiCodeGeneratorService first =
                factory.getWorkflowAiCodeGeneratorService(HTML_APP_ID, CodeGenTypeEnum.HTML);
        AiCodeGeneratorService second =
                factory.getWorkflowAiCodeGeneratorService(HTML_APP_ID, CodeGenTypeEnum.HTML);

        assertNotSame(first, second, "工作流每次生成都应使用独立代理和独立记忆");
        verifyNoInteractions(conversationMemoryCache);
    }

    @Test
    void rejectsInvalidAppIdAndCodeGenType() {
        assertThrows(BusinessException.class,
                () -> factory.getAiCodeGeneratorService(null, CodeGenTypeEnum.HTML));
        assertThrows(BusinessException.class,
                () -> factory.getAiCodeGeneratorService(0L, CodeGenTypeEnum.HTML));
        assertThrows(BusinessException.class,
                () -> factory.getAiCodeGeneratorService(HTML_APP_ID, null));
    }

    @Test
    void htmlModeUsesOpenAiStreamingModelAndChatMemoryProvider() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(50);
        when(chatModel.supportedCapabilities()).thenReturn(Set.of());
        when(conversationMemoryCache.get(HTML_APP_ID)).thenReturn(memory);
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            assertTrue(request.toolSpecifications().isEmpty(), "HTML 模式不应绑定任何文件工具");
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("html-chunk");
            handler.onCompleteResponse(response(AiMessage.from("html-chunk")));
            return null;
        }).when(openAiStreamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        List<String> chunks = factory.getAiCodeGeneratorService(HTML_APP_ID, CodeGenTypeEnum.HTML)
                .generateHtmlCodeStream(HTML_APP_ID, "生成一个页面")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertEquals(List.of("html-chunk"), chunks);
        verify(openAiStreamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(reasoningStreamingChatModel, never())
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(conversationMemoryCache).get(HTML_APP_ID);
        assertTrue(memory.messages().stream().anyMatch(UserMessage.class::isInstance),
                "对话记忆必须收到本轮用户消息");
        assertTrue(memory.messages().stream().anyMatch(AiMessage.class::isInstance),
                "对话记忆必须收到模型回复");
    }

    @Test
    void vueModeUsesReasoningModelToolsAndInjectedAppId() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(50);
        when(chatModel.supportedCapabilities()).thenReturn(Set.of());
        when(conversationMemoryCache.get(vueAppId)).thenReturn(memory);
        AtomicInteger modelCalls = new AtomicInteger();
        doAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            if (modelCalls.getAndIncrement() == 0) {
                // 增量修改需要同时具备目录读取、文件读取、修改、写入和删除工具。
                Set<String> expectedTools = Set.of(FileWriteTool.TOOL_NAME, FileReadTool.TOOL_NAME,
                        FileDirReadTool.TOOL_NAME, FileModifyTool.TOOL_NAME, FileDeleteTool.TOOL_NAME);
                Set<String> actualTools = request.toolSpecifications().stream()
                        .map(specification -> specification.name())
                        .collect(Collectors.toSet());
                assertEquals(expectedTools, actualTools, "Vue 模式的模型请求必须携带全部文件工具");
                ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                        .id("call-1")
                        .name(FileWriteTool.TOOL_NAME)
                        .arguments("{\"relativeFilePath\":\"src/App.vue\","
                                + "\"content\":\"<template><div>你好</div></template>\"}")
                        .build();
                handler.onCompleteResponse(response(AiMessage.from(toolRequest)));
            } else {
                handler.onPartialResponse("生成完毕");
                handler.onCompleteResponse(response(AiMessage.from("生成完毕")));
            }
            return null;
        }).when(reasoningStreamingChatModel)
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        TokenStream tokenStream = factory.getAiCodeGeneratorService(vueAppId, CodeGenTypeEnum.VUE_PROJECT)
                .generateVueProjectCodeStream(vueAppId, "生成一个 Vue 项目");
        List<String> chunks = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        Flux<String> flux = Flux.create(sink -> tokenStream
                .onPartialResponse(sink::next)
                .onToolExecuted(execution -> {
                    toolResults.add(execution.result());
                    sink.next("[工具] " + execution.result());
                })
                .onCompleteResponse(response -> sink.complete())
                .onError(sink::error)
                .start());

        assertEquals(List.of("[工具] 文件写入成功：src/App.vue", "生成完毕"),
                flux.collectList().block(Duration.ofSeconds(5)));
        assertEquals(List.of("文件写入成功：src/App.vue"), toolResults);
        assertTrue(Files.isRegularFile(FileWriteTool.projectRoot(vueAppId).resolve("src/App.vue")),
                "工具必须把文件写入该 appId 对应的工程目录");
        verify(reasoningStreamingChatModel, times(2))
                .chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verifyNoInteractions(openAiStreamingChatModel);
        verify(conversationMemoryCache).get(vueAppId);
        // 用户消息、工具请求、工具结果、最终回复都应写入同一份记忆，供下一轮工具调用使用。
        assertTrue(memory.messages().size() >= 4, "工具调用链必须完整写入对话记忆");
        List<String> messageTexts = memory.messages().stream()
                .map(ChatMessage::toString)
                .toList();
        assertTrue(messageTexts.stream().anyMatch(text -> text.contains("writeFile")),
                "对话记忆必须保留工具调用请求");
    }

    private static ChatResponse response(AiMessage aiMessage) {
        return ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(1, 1))
                        .build())
                .build();
    }
}
