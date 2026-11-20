package com.zgq.zgqaicodemother.security.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 限流器测试：Redis 正常路径、本地兜底、故障降级、窗口过期和容量上限。
 *
 * <p>所有时间都由可注入的 {@link AtomicLong} 推进，不连接真实 Redis，也不使用 sleep。</p>
 */
class RateLimiterTest {

    private static final int PERMITS = 2;

    private static final long WINDOW_SECONDS = 60L;

    private StringRedisTemplate redisTemplate;

    private RateLimitProperties properties;

    private AtomicLong clock;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        properties = new RateLimitProperties();
        clock = new AtomicLong(1_000_000L);
        rateLimiter = new RateLimiter(redisTemplate, properties, clock::get);
    }

    @Test
    void enforcesRedisFixedWindowAndReadsRetryAfterFromTtl() {
        stubRedisCounts();
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.MILLISECONDS))).thenReturn(12_500L);

        RateLimitDecision first = rateLimiter.acquire("chat-message", PERMITS, WINDOW_SECONDS);
        RateLimitDecision second = rateLimiter.acquire("chat-message", PERMITS, WINDOW_SECONDS);
        RateLimitDecision third = rateLimiter.acquire("chat-message", PERMITS, WINDOW_SECONDS);

        assertTrue(first.allowed());
        assertEquals(1L, first.remaining());
        assertTrue(second.allowed());
        assertEquals(0L, second.remaining());
        assertFalse(third.allowed());
        assertEquals(0L, third.remaining());
        // Redis TTL 向上取整为 13 秒，且不会超过配置窗口。
        assertEquals(13L, third.retryAfterSeconds());
    }

    @Test
    void localFallbackStillLimitsAfterRedisFailure() {
        stubRedisFailure();

        RateLimitDecision first = rateLimiter.acquire("local-window", PERMITS, WINDOW_SECONDS);
        RateLimitDecision second = rateLimiter.acquire("local-window", PERMITS, WINDOW_SECONDS);
        RateLimitDecision third = rateLimiter.acquire("local-window", PERMITS, WINDOW_SECONDS);

        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertFalse(third.allowed());
        assertTrue(third.retryAfterSeconds() >= 1L);
        assertTrue(third.retryAfterSeconds() <= WINDOW_SECONDS);
    }

    @Test
    void localWindowRecoversAfterWindowExpires() {
        stubRedisFailure();

        assertTrue(rateLimiter.acquire("expiring-window", 1, WINDOW_SECONDS).allowed());
        assertFalse(rateLimiter.acquire("expiring-window", 1, WINDOW_SECONDS).allowed());

        clock.addAndGet(WINDOW_SECONDS * 1000L);

        assertTrue(rateLimiter.acquire("expiring-window", 1, WINDOW_SECONDS).allowed());
    }

    @Test
    void failOpenFalseAllowsRequestsWhenRedisIsUnavailable() {
        stubRedisFailure();
        properties.setFailOpen(false);

        assertTrue(rateLimiter.acquire("fail-closed", 1, WINDOW_SECONDS).allowed());
        assertTrue(rateLimiter.acquire("fail-closed", 1, WINDOW_SECONDS).allowed());
        // 失败后没有切到本地计数，仍然继续尝试 Redis，便于 Redis 恢复后自动恢复分布式限流。
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void differentKeysHaveIndependentLocalWindows() {
        stubRedisFailure();

        assertTrue(rateLimiter.acquire("key-a", 1, WINDOW_SECONDS).allowed());
        assertTrue(rateLimiter.acquire("key-b", 1, WINDOW_SECONDS).allowed());
        assertFalse(rateLimiter.acquire("key-a", 1, WINDOW_SECONDS).allowed());
    }

    @Test
    void localWindowMapDoesNotGrowBeyondConfiguredLimit() {
        stubRedisFailure();
        properties.setMaxTrackedKeys(3);

        for (int i = 0; i < 50; i++) {
            assertTrue(rateLimiter.acquire("bounded-" + i, 5, WINDOW_SECONDS).allowed());
        }

        @SuppressWarnings("unchecked")
        Map<String, ?> localWindows = (Map<String, ?>) ReflectionTestUtils.getField(
                rateLimiter, "localWindows");
        assertNotNull(localWindows);
        assertTrue(localWindows.size() <= 3);
    }

    @Test
    void handlesNullKeyAndInvalidLimitsWithoutThrowing() {
        stubRedisFailure();

        RateLimitDecision decision = assertDoesNotThrow(
                () -> rateLimiter.acquire(null, 0, 0));

        assertTrue(decision.allowed());
    }

    @Test
    void disabledLimiterAllowsWithoutTouchingRedis() {
        properties.setEnabled(false);

        RateLimitDecision decision = rateLimiter.acquire("disabled", 1, WINDOW_SECONDS);

        assertTrue(decision.allowed());
        verifyNoInteractions(redisTemplate);
    }

    private void stubRedisFailure() {
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
    }

    private void stubRedisCounts() {
        // 模拟窗口内连续三次 INCR 的返回值，第三次会超过 permits=2。
        doReturn(1L, 2L, 3L)
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any());
    }
}
