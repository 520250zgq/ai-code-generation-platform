package com.zgq.zgqaicodemother.core.handler;

import com.zgq.zgqaicodemother.ai.tools.FileDeleteTool;
import com.zgq.zgqaicodemother.ai.tools.FileDirReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileModifyTool;
import com.zgq.zgqaicodemother.ai.tools.FileReadTool;
import com.zgq.zgqaicodemother.ai.tools.FileWriteTool;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Vue TokenStream 处理器测试：验证流式输出、工具进度、循环上限，以及完成后的异步构建触发。
 */
@ExtendWith(MockitoExtension.class)
class JsonMessageStreamHandlerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private VueProjectBuilder vueProjectBuilder;

    private JsonMessageStreamHandler handler;

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    @BeforeEach
    void setUp() {
        ToolManager toolManager = new ToolManager(List.of(
                new FileWriteTool(), new FileReadTool(), new FileDirReadTool(),
                new FileModifyTool(), new FileDeleteTool()));
        handler = new JsonMessageStreamHandler(vueProjectBuilder, toolManager);
    }

    @Test
    void streamsPartialTextAndToolProgressThenTriggersBuild() {
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
            stream.emitPartial("生成计划：先创建配置文件。\n");
            stream.emitTool("文件写入成功：package.json");
            stream.emitTool("文件写入成功：src/App.vue");
            stream.emitPartial("\nVue 工程生成完毕");
            stream.emitComplete();
        });

        List<String> chunks = handler.handle(tokenStream, appId).collectList().block(TIMEOUT);

        assertEquals(List.of(
                "生成计划：先创建配置文件。\n",
                "\n\n> 📄 文件写入成功：package.json\n\n",
                "\n\n> 📄 文件写入成功：src/App.vue\n\n",
                "\nVue 工程生成完毕"
        ), chunks);
        String response = String.join("", chunks);
        assertFalse(response.contains("code_output"), "工具进度不能暴露服务器目录");
        verify(vueProjectBuilder).buildProjectAsync(FileWriteTool.projectRoot(appId).toString());
    }

    @Test
    void formatsEachToolProgressUsingItsOwnStrategy() {
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
            stream.emitTool(FileDirReadTool.TOOL_NAME,
                    "目录读取成功：src，共 2 个文件，1 个目录\nsrc/\n  App.vue");
            stream.emitTool(FileReadTool.TOOL_NAME,
                    "文件读取成功：src/App.vue（20 字符）\n文件内容：\n<template><div /></template>");
            stream.emitTool(FileModifyTool.TOOL_NAME,
                    "文件修改成功：src/App.vue（替换 1 处）\n旧内容摘要：\na\n新内容摘要：\nb");
            stream.emitTool(FileDeleteTool.TOOL_NAME, "文件删除成功：src/Old.vue");
            stream.emitComplete();
        });

        List<String> chunks = handler.handle(tokenStream, appId).collectList().block(TIMEOUT);

        assertEquals(List.of(
                "\n\n> 📂 目录读取成功：src，共 2 个文件，1 个目录\n\n",
                "\n\n> 📖 文件读取成功：src/App.vue（20 字符）\n\n",
                "\n\n> ✏️ 文件修改成功：src/App.vue（替换 1 处）\n\n",
                "\n\n> 🗑️ 文件删除成功：src/Old.vue\n\n"
        ), chunks);
        String response = String.join("", chunks);
        assertFalse(response.contains("<template>"), "读取工具不能在流式进度中暴露完整文件内容");
        assertFalse(response.contains("旧内容摘要"), "修改工具只应展示首行摘要");
        verify(vueProjectBuilder).buildProjectAsync(FileWriteTool.projectRoot(appId).toString());
    }

    @Test
    void stopsInfiniteToolLoopAtConfiguredLimitAndDoesNotBuild() {
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
            for (int index = 0; index <= JsonMessageStreamHandler.MAX_TOOL_EXECUTIONS_PER_TURN + 5; index++) {
                try {
                    stream.emitTool("文件写入成功：src/File" + index + ".vue");
                } catch (RuntimeException expected) {
                    // Handler 抛出内部异常以中止 LangChain4j 的流式工具循环。
                    return;
                }
            }
            stream.emitComplete();
        });

        List<String> emitted = new ArrayList<>();
        Flux<String> stream = handler.handle(tokenStream, appId).doOnNext(emitted::add);

        BusinessException error = assertThrows(BusinessException.class, () -> stream.blockLast(TIMEOUT));
        assertTrue(error.getMessage().contains("工具调用次数超过上限"), error.getMessage());
        assertEquals(JsonMessageStreamHandler.MAX_TOOL_EXECUTIONS_PER_TURN, emitted.size(),
                "超限后不应继续向客户端发送工具进度");
        verifyNoInteractions(vueProjectBuilder);
    }

    @Test
    void propagatesModelErrorAndDoesNotBuild() {
        IllegalStateException expected = new IllegalStateException("model unavailable");
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> stream.emitError(expected));

        IllegalStateException actual = assertThrows(IllegalStateException.class,
                () -> handler.handle(tokenStream, appId).blockLast(TIMEOUT));

        assertSame(expected, actual);
        verifyNoInteractions(vueProjectBuilder);
    }

    @Test
    void stripsServerStorageRootFromToolProgress() {
        String absolutePath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId + "/src/App.vue";
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
            stream.emitTool("文件写入成功：" + absolutePath);
            stream.emitComplete();
        });

        List<String> chunks = handler.handle(tokenStream, appId).collectList().block(TIMEOUT);

        String response = String.join("", chunks);
        assertFalse(response.contains("code_output"), "工具进度不能包含存储根目录");
        assertTrue(response.contains("src/App.vue"), "仍应保留可读的相对路径");
    }

    @Test
    void skipsBuildWhenSubscriberCancels() {
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
            stream.emitPartial("第一段");
            stream.emitComplete();
        });

        List<String> chunks = handler.handle(tokenStream, appId).take(1).collectList().block(TIMEOUT);

        assertEquals(List.of("第一段"), chunks);
        verifyNoInteractions(vueProjectBuilder);
    }

    @Test
    void buildTriggerFailureDoesNotBreakCompletedStream() {
        doThrow(new IllegalStateException("builder unavailable"))
                .when(vueProjectBuilder).buildProjectAsync(anyString());
        FakeTokenStream tokenStream = new FakeTokenStream(stream -> {
            stream.emitPartial("完成");
            stream.emitComplete();
        });

        assertEquals(List.of("完成"), handler.handle(tokenStream, appId).collectList().block(TIMEOUT));
    }

    @Test
    void rejectsNullTokenStreamAndInvalidAppId() {
        assertThrows(BusinessException.class, () -> handler.handle(null, appId).blockLast(TIMEOUT));
        FakeTokenStream tokenStream = new FakeTokenStream(FakeTokenStream::emitComplete);
        assertThrows(BusinessException.class, () -> handler.handle(tokenStream, null).blockLast(TIMEOUT));
        assertThrows(BusinessException.class, () -> handler.handle(tokenStream, 0L).blockLast(TIMEOUT));
        verifyNoInteractions(vueProjectBuilder);
    }

    /**
     * 测试用 TokenStream：按脚本同步触发回调，模拟 LangChain4j 流式模型的回调时序。
     */
    private static final class FakeTokenStream implements TokenStream {

        private final Consumer<FakeTokenStream> script;
        private Consumer<String> partialResponseHandler;
        private Consumer<ToolExecution> toolExecutionHandler;
        private Consumer<ChatResponse> completeResponseHandler;
        private Consumer<Throwable> errorHandler;

        private FakeTokenStream(Consumer<FakeTokenStream> script) {
            this.script = script;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
            this.partialResponseHandler = partialResponseHandler;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> contentHandler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
            this.toolExecutionHandler = toolExecuteHandler;
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler) {
            this.completeResponseHandler = completeResponseHandler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            script.accept(this);
        }

        private void emitPartial(String text) {
            partialResponseHandler.accept(text);
        }

        private void emitTool(String result) {
            emitTool(FileWriteTool.TOOL_NAME, result);
        }

        private void emitTool(String toolName, String result) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("call")
                    .name(toolName)
                    .arguments("{}")
                    .build();
            toolExecutionHandler.accept(ToolExecution.builder()
                    .request(request)
                    .result(result)
                    .build());
        }

        private void emitComplete() {
            completeResponseHandler.accept(ChatResponse.builder()
                    .aiMessage(AiMessage.from(""))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(1, 1))
                            .build())
                    .build());
        }

        private void emitError(Throwable error) {
            errorHandler.accept(error);
        }
    }
}
