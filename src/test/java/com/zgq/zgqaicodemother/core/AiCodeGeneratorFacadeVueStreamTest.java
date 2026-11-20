package com.zgq.zgqaicodemother.core;

import com.zgq.zgqaicodemother.ai.AiCodeGeneratorService;
import com.zgq.zgqaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryGuard;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryProperties;
import com.zgq.zgqaicodemother.ai.tools.ToolCallGuard;
import com.zgq.zgqaicodemother.ai.tools.ToolCallProperties;
import com.zgq.zgqaicodemother.core.handler.JsonMessageStreamHandler;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.preview.PreviewService;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Vue 分支的门面测试：确认 TokenStream 交给 JSON 消息流处理器，门面本身不再重复处理。
 */
@ExtendWith(MockitoExtension.class)
class AiCodeGeneratorFacadeVueStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Mock
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Mock
    private JsonMessageStreamHandler jsonMessageStreamHandler;

    @InjectMocks
    private AiCodeGeneratorFacade facade;

    private final long appId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);

    /**
     * 注入真实的护轨与保护器（重试关闭、工具预算默认值），只把预览状态服务替换为 mock。
     * 用真实实例而不是 mock，可以顺带保证“每轮生成登记/清理工具预算”的逻辑可运行。
     */
    @BeforeEach
    void setUpFacadeDependencies() {
        AiRetryProperties retryProperties = new AiRetryProperties();
        retryProperties.setEnabled(false);
        ReflectionTestUtils.setField(facade, "aiRetryGuard", new AiRetryGuard(retryProperties));
        ReflectionTestUtils.setField(facade, "toolCallGuard", new ToolCallGuard(new ToolCallProperties()));
        ReflectionTestUtils.setField(facade, "previewService", mock(PreviewService.class));
    }

    @Test
    void delegatesVueTokenStreamToJsonMessageStreamHandler() {
        TokenStream tokenStream = mock(TokenStream.class);
        when(aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(aiCodeGeneratorService);
        when(aiCodeGeneratorService.generateVueProjectCodeStream(appId, "生成作品集网站"))
                .thenReturn(tokenStream);
        when(jsonMessageStreamHandler.handle(tokenStream, appId))
                .thenReturn(Flux.just("生成计划", "生成完毕"));

        List<String> chunks = facade.generateAndSaveCodeStream(
                        "生成作品集网站", CodeGenTypeEnum.VUE_PROJECT, appId)
                .collectList()
                .block(TIMEOUT);

        assertEquals(List.of("生成计划", "生成完毕"), chunks);
        verify(jsonMessageStreamHandler).handle(tokenStream, appId);
    }

    @Test
    void rejectsInvalidRequestBeforeResolvingAiService() {
        assertThrows(BusinessException.class,
                () -> facade.generateAndSaveCodeStream("生成", null, appId));
        assertThrows(BusinessException.class,
                () -> facade.generateAndSaveCodeStream("生成", CodeGenTypeEnum.VUE_PROJECT, null));
        assertThrows(BusinessException.class,
                () -> facade.generateAndSaveCodeStream("生成", CodeGenTypeEnum.VUE_PROJECT, 0L));
        verifyNoInteractions(aiCodeGeneratorServiceFactory, jsonMessageStreamHandler);
    }
}
