package com.zgq.zgqaicodemother.service.impl;

import com.mybatisflex.core.paginate.Page;
import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.ai.concurrency.AiConcurrencyLimiter;
import com.zgq.zgqaicodemother.ai.concurrency.AiConcurrencyProperties;
import com.zgq.zgqaicodemother.ai.memory.ConversationMemoryCache;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryGuard;
import com.zgq.zgqaicodemother.ai.resilience.AiRetryProperties;
import com.zgq.zgqaicodemother.cache.GoodAppListCache;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import com.zgq.zgqaicodemother.preview.PreviewService;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import com.zgq.zgqaicodemother.service.ScreenshotService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 应用服务层的系统级优化行为测试：精选列表缓存、预览状态联动和 AI 生成并发互斥。
 *
 * <p>这些行为跨越多个协作对象，单独测缓存或并发组件不够；这里用真实的并发控制器与重试护轨
 * 配合 mock 的持久化层，验证“接线是否正确”。</p>
 */
class AppServiceImplOptimizationTest {

    private static final long APP_ID = 101L;
    private static final long OWNER_ID = 7L;

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade = mock(AiCodeGeneratorFacade.class);

    private final ConversationMemoryCache conversationMemoryCache = mock(ConversationMemoryCache.class);

    private final GoodAppListCache goodAppListCache = mock(GoodAppListCache.class);

    private final PreviewService previewService = mock(PreviewService.class);

    private final AiConcurrencyLimiter aiConcurrencyLimiter = new AiConcurrencyLimiter(concurrencyProperties());

    private AppServiceImpl appService;

    private App app;

    private User loginUser;

    @BeforeEach
    void setUp() {
        appService = org.mockito.Mockito.spy(new AppServiceImpl());
        ReflectionTestUtils.setField(appService, "chatHistoryService", chatHistoryService);
        ReflectionTestUtils.setField(appService, "aiCodeGeneratorFacade", aiCodeGeneratorFacade);
        ReflectionTestUtils.setField(appService, "aiCodeGenTypeRoutingService",
                mock(AiCodeGenTypeRoutingService.class));
        ReflectionTestUtils.setField(appService, "conversationMemoryCache", conversationMemoryCache);
        ReflectionTestUtils.setField(appService, "screenshotService", mock(ScreenshotService.class));
        ReflectionTestUtils.setField(appService, "goodAppListCache", goodAppListCache);
        ReflectionTestUtils.setField(appService, "previewService", previewService);
        ReflectionTestUtils.setField(appService, "aiConcurrencyLimiter", aiConcurrencyLimiter);
        AiRetryProperties retryProperties = new AiRetryProperties();
        retryProperties.setEnabled(false);
        ReflectionTestUtils.setField(appService, "aiRetryGuard", new AiRetryGuard(retryProperties));
        ReflectionTestUtils.setField(appService, "deployHost", "http://localhost:8123");

        loginUser = new User();
        loginUser.setId(OWNER_ID);
        app = new App();
        app.setId(APP_ID);
        app.setUserId(OWNER_ID);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        app.setAppName("测试应用");
    }

    @Test
    void goodAppListServesCacheHitWithoutTouchingDatabase() {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(1);
        request.setPageSize(6);
        Page<AppVO> cachedPage = Page.of(1, 6, 1);
        cachedPage.setRecords(List.of(new AppVO()));
        when(goodAppListCache.find(request)).thenReturn(Optional.of(cachedPage));

        Page<AppVO> result = appService.listGoodAppVOPage(request);

        assertSame(cachedPage, result);
        verify(appService, never()).listAppByPage(any());
    }

    @Test
    void goodAppListLoadsFromDatabaseAndFillsCacheOnMiss() {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(1);
        request.setPageSize(6);
        Page<App> entities = Page.of(1, 6, 1);
        Page<AppVO> views = Page.of(1, 6, 1);
        when(goodAppListCache.find(request)).thenReturn(Optional.empty());
        doReturn(entities).when(appService).listAppByPage(request);
        doReturn(views).when(appService).getAppVOPage(entities);

        Page<AppVO> result = appService.listGoodAppVOPage(request);

        assertSame(views, result);
        verify(goodAppListCache).put(request, views);
    }

    @Test
    void deletingAppClearsCacheMemoryAndPreviewState() {
        doReturn(true).when(appService).removeById(APP_ID);

        assertTrue(appService.removeAppWithChatHistory(APP_ID));

        verify(conversationMemoryCache).evict(APP_ID);
        verify(previewService).evict(APP_ID);
        verify(goodAppListCache).invalidate();
    }

    @Test
    void chatRejectsSecondConcurrentGenerationForSameApp() throws Exception {
        stubChatGeneration(Flux.just("chunk"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 应用级许可按线程互斥，必须由另一个线程持有才能模拟真实的并发生成。
        Thread holder = Thread.ofVirtual().start(() -> {
            try (AiConcurrencyLimiter.Lease ignored = aiConcurrencyLimiter.acquire(APP_ID)) {
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(2, TimeUnit.SECONDS));

        try {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> appService.chatToGenCode(APP_ID, "把标题改大一点", loginUser)
                            .blockLast(Duration.ofSeconds(5)));
            assertEquals(ErrorCode.SERVICE_BUSY.getCode(), error.getCode());
            assertTrue(error.getMessage().contains("正在生成中"), error.getMessage());
        } finally {
            release.countDown();
            holder.join(2000);
        }

        // 上一轮结束后必须能继续生成，证明许可证没有泄漏。
        assertEquals(List.of("chunk"), appService.chatToGenCode(APP_ID, "继续优化", loginUser)
                .collectList().block(Duration.ofSeconds(5)));
    }

    @Test
    void chatReleasesPermitWhenStreamFails() {
        stubChatGeneration(Flux.error(new IllegalStateException("model down")));

        assertThrows(IllegalStateException.class,
                () -> appService.chatToGenCode(APP_ID, "生成失败", loginUser).blockLast(Duration.ofSeconds(5)));

        assertEquals(0, aiConcurrencyLimiter.activeRequests(),
                "流异常后必须归还全局并发许可，否则连续失败几次就把服务锁死");
        try (AiConcurrencyLimiter.Lease lease = aiConcurrencyLimiter.acquire(APP_ID)) {
            assertEquals(APP_ID, lease.appId());
        }
        assertEquals(0, aiConcurrencyLimiter.activeRequests());
    }

    private void stubChatGeneration(Flux<String> stream) {
        doReturn(app).when(appService).getById(APP_ID);
        when(conversationMemoryCache.get(APP_ID)).thenReturn(MessageWindowChatMemory.withMaxMessages(10));
        when(aiCodeGeneratorFacade.generateAndSaveCodeStream(any(), any(), anyLong()))
                .thenReturn(stream);
    }

    private static AiConcurrencyProperties concurrencyProperties() {
        AiConcurrencyProperties properties = new AiConcurrencyProperties();
        properties.setEnabled(true);
        properties.setMaxConcurrentRequests(4);
        properties.setAcquireTimeout(Duration.ofMillis(200));
        properties.setPerAppAcquireTimeout(Duration.ofMillis(100));
        return properties;
    }
}
