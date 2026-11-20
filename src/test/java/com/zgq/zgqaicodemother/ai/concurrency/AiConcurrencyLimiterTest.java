package com.zgq.zgqaicodemother.ai.concurrency;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 并发控制器测试：全局并发上限、同一应用互斥、许可证归还与关闭开关后的行为。
 *
 * <p>所有用例都在同一进程内用真实信号量验证，不使用 sleep 等待业务结果，
 * 只对“必须超时”的路径设置很短的等待时间。</p>
 */
class AiConcurrencyLimiterTest {

    @Test
    void allowsConfiguredNumberOfConcurrentRequests() {
        AiConcurrencyProperties properties = properties(2, Duration.ofMillis(200), Duration.ofMillis(200));
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(properties);

        AiConcurrencyLimiter.Lease first = limiter.acquire(1L);
        AiConcurrencyLimiter.Lease second = limiter.acquire(2L);

        assertEquals(2, limiter.activeRequests(), "两个不同应用应当可以同时生成");

        // 第三个应用必须排队并在超时后失败，而不是突破并发上限。
        BusinessException error = assertThrows(BusinessException.class, () -> limiter.acquire(3L));
        assertEquals(ErrorCode.SERVICE_BUSY.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("繁忙"), error.getMessage());

        first.close();
        second.close();
        assertEquals(0, limiter.activeRequests());
    }

    @Test
    void rejectsConcurrentGenerationForTheSameApp() throws Exception {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(
                properties(8, Duration.ofMillis(100), Duration.ofMillis(50)));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 应用锁是线程级互斥：必须由另一个线程持有，才能模拟“同一应用两轮并发生成”。
        Thread holder = Thread.ofVirtual().start(() -> {
            try (AiConcurrencyLimiter.Lease ignored = limiter.acquire(42L)) {
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(2, TimeUnit.SECONDS));

        BusinessException error = assertThrows(BusinessException.class, () -> limiter.acquire(42L));
        assertTrue(error.getMessage().contains("正在生成中"), error.getMessage());

        release.countDown();
        holder.join(2000);
        // 释放后同一应用可以重新获取，说明互斥不是永久性的。
        try (AiConcurrencyLimiter.Lease ignored = limiter.acquire(42L)) {
            assertEquals(42L, ignored.appId());
        }
    }

    @Test
    void leaseCloseIsIdempotent() {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(
                properties(1, Duration.ofMillis(100), Duration.ofMillis(100)));

        AiConcurrencyLimiter.Lease lease = limiter.acquire(7L);
        lease.close();
        lease.close();
        lease.close();

        assertEquals(0, limiter.activeRequests(), "重复关闭不能把信号量计数放大");
        assertEquals(1, limiter.maxConcurrentRequests());
    }

    @Test
    void disabledLimiterStillSerializesTheSameApp() throws Exception {
        AiConcurrencyProperties properties = properties(8, Duration.ofMillis(100), Duration.ofMillis(50));
        properties.setEnabled(false);
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(properties);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> {
            try (AiConcurrencyLimiter.Lease ignored = limiter.acquire(9L)) {
                holding.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(2, TimeUnit.SECONDS));

        assertFalse(limiter.enabled());
        assertThrows(BusinessException.class, () -> limiter.acquire(9L),
                "关闭并发限制时仍必须保证同一应用不并发生成");
        // 关闭全局限制后不同应用互不影响，也不再消耗全局许可。
        try (AiConcurrencyLimiter.Lease ignored = limiter.acquire(10L)) {
            assertEquals(10L, ignored.appId());
        }
        release.countDown();
        holder.join(2000);
        assertEquals(0, limiter.activeRequests());
    }

    @Test
    void applicationLockIsReleasedWhenGlobalPermitTimesOut() throws Exception {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(
                properties(1, Duration.ofMillis(50), Duration.ofMillis(50)));
        AiConcurrencyLimiter.Lease lease = limiter.acquire(1L);

        // 另一个应用拿不到全局许可，异常路径必须把已经获取的应用锁还回去。
        assertThrows(BusinessException.class, () -> limiter.acquire(2L));
        lease.close();

        AiConcurrencyLimiter.Lease retried = limiter.acquire(2L);
        retried.close();
        assertEquals(0, limiter.activeRequests());
    }

    @Test
    void waitingRequestProceedsWhenPermitIsReleased() throws Exception {
        AiConcurrencyLimiter limiter = new AiConcurrencyLimiter(
                properties(1, Duration.ofSeconds(2), Duration.ofSeconds(2)));
        AiConcurrencyLimiter.Lease first = limiter.acquire(1L);
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread waiter = Thread.ofVirtual().start(() -> {
            try (AiConcurrencyLimiter.Lease second = limiter.acquire(2L)) {
                acquired.countDown();
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        // 先确认等待方确实在排队（未立即获得许可），再释放第一个许可。
        assertFalse(acquired.await(100, TimeUnit.MILLISECONDS), "并发已满时不应立即获得许可");
        first.close();

        assertTrue(acquired.await(2, TimeUnit.SECONDS), "释放许可后排队请求应当被唤醒");
        waiter.join(2000);
        assertNull(failure.get());
        assertEquals(0, limiter.activeRequests());
    }

    private static AiConcurrencyProperties properties(int maxConcurrent,
                                                      Duration acquireTimeout,
                                                      Duration perAppTimeout) {
        AiConcurrencyProperties properties = new AiConcurrencyProperties();
        properties.setEnabled(true);
        properties.setMaxConcurrentRequests(maxConcurrent);
        properties.setAcquireTimeout(acquireTimeout);
        properties.setPerAppAcquireTimeout(perAppTimeout);
        return properties;
    }
}
