package com.zgq.zgqaicodemother.ai.memory;

import com.zgq.zgqaicodemother.service.ChatHistoryService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话记忆并发测试。
 *
 * <p>优化前所有应用的记忆初始化都在同一把全局锁里执行，一次 Redis/MySQL 慢查询会把
 * 所有 AI 请求串行化。这里用“加载一个应用时阻塞”的方式锁定该行为：另一个应用的获取必须
 * 不受影响，同一应用的并发首访只能回源一次。</p>
 */
class ConversationMemoryCacheConcurrencyTest {

    private final ChatMemoryStore chatMemoryStore = mock(ChatMemoryStore.class);

    private final ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);

    @Test
    void slowLoadOfOneAppDoesNotBlockAnotherApp() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(chatMemoryStore.getMessages(1L)).thenAnswer(invocation -> {
            loadStarted.countDown();
            // 模拟一次慢速 Redis 读取；只有本应用的首访会等待，其他应用必须能继续。
            releaseLoad.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        when(chatHistoryService.listRecentMessages(anyLong(), anyInt())).thenReturn(List.of());
        ConversationMemoryCache cache = new ConversationMemoryCache(
                chatMemoryStore, chatHistoryService, properties());

        Thread loadingApp = Thread.ofVirtual().start(() -> cache.get(1L));
        assertTrue(loadStarted.await(2, TimeUnit.SECONDS), "第一个应用的加载没有开始");

        long startNanos = System.nanoTime();
        assertNotNull(cache.get(2L));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertTrue(elapsedMillis < 1000,
                "另一个应用的记忆加载被第一个应用阻塞了 " + elapsedMillis + " 毫秒，说明还存在全局锁");

        releaseLoad.countDown();
        loadingApp.join(2000);
    }

    @Test
    void concurrentFirstAccessLoadsTheSameAppOnlyOnce() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(chatMemoryStore.getMessages(3L)).thenAnswer(invocation -> {
            loadStarted.countDown();
            releaseLoad.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        when(chatHistoryService.listRecentMessages(anyLong(), anyInt())).thenReturn(List.of());
        ConversationMemoryCache cache = new ConversationMemoryCache(
                chatMemoryStore, chatHistoryService, properties());
        AtomicReference<Object> firstResult = new AtomicReference<>();
        AtomicReference<Object> secondResult = new AtomicReference<>();

        Thread first = Thread.ofVirtual().start(() -> firstResult.set(cache.get(3L)));
        assertTrue(loadStarted.await(2, TimeUnit.SECONDS));
        Thread second = Thread.ofVirtual().start(() -> secondResult.set(cache.get(3L)));
        // 给第二个线程足够时间进入等待，然后放行加载。
        Thread.sleep(100);
        releaseLoad.countDown();
        first.join(2000);
        second.join(2000);

        assertSame(firstResult.get(), secondResult.get(),
                "同一应用的并发首访必须复用同一份记忆，否则两轮生成会各写一份上下文");
        verify(chatMemoryStore, times(1)).getMessages(3L);
    }

    @Test
    void evictRemovesCachedMemory() {
        when(chatHistoryService.listRecentMessages(anyLong(), anyInt())).thenReturn(List.of());
        ConversationMemoryCache cache = new ConversationMemoryCache(
                chatMemoryStore, chatHistoryService, properties());

        cache.get(5L);
        assertTrue(cache.cachedCount() >= 1);

        cache.evict(5L);

        assertTrue(cache.cachedCount() == 0, "删除应用时必须同时清理本地记忆");
        verify(chatMemoryStore).deleteMessages(5L);
    }

    @Test
    void failedLoadKeepsOtherAppsUsable() throws Exception {
        when(chatMemoryStore.getMessages(7L)).thenThrow(new IllegalStateException("redis timeout"));
        when(chatHistoryService.listRecentMessages(anyLong(), anyInt())).thenReturn(List.of());
        ConversationMemoryCache cache = new ConversationMemoryCache(
                chatMemoryStore, chatHistoryService, properties());

        try {
            cache.get(7L);
        } catch (IllegalStateException expected) {
            // 加载失败会把异常抛给当前请求，但必须归还应用级加载锁。
        }

        // Redis 恢复后重试同一应用：如果加载锁泄漏，这里会等满 200ms 并抛出“加载超时”。
        // 必须用 doReturn 重新打桩：when(mock.method()) 会先真实调用一次 mock，
        // 而旧桩会立刻抛出刚才的异常。
        doReturn(List.of()).when(chatMemoryStore).getMessages(7L);
        assertNotNull(cache.get(7L));
    }

    private static ChatMemoryProperties properties() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setMaxMessages(10);
        properties.setMaxCachedConversations(10);
        properties.setLoadTimeout(Duration.ofMillis(200));
        return properties;
    }
}
