package com.zgq.zgqaicodemother.ai.resilience;

import com.zgq.zgqaicodemother.ai.tools.ToolCallGuard;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试护轨测试：只在“尚未输出内容 + 可恢复错误”时重试，业务错误和部分输出一律不重试。
 */
class AiRetryGuardTest {

    @Test
    void retriesTransientFailureBeforeAnyOutput() {
        AiRetryGuard guard = guard(3);
        AtomicInteger attempts = new AtomicInteger();

        List<String> chunks = guard.guardStream("html", () -> {
            if (attempts.incrementAndGet() == 1) {
                return Flux.error(new IOException("connection reset"));
            }
            return Flux.just("a", "b");
        }).collectList().block(Duration.ofSeconds(5));

        assertEquals(List.of("a", "b"), chunks);
        assertEquals(2, attempts.get(), "首次失败且尚未输出内容时应当重试一次");
    }

    @Test
    void doesNotRetryAfterContentWasEmitted() {
        AiRetryGuard guard = guard(3);
        AtomicInteger attempts = new AtomicInteger();
        List<String> received = new ArrayList<>();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> guard.guardStream("html", () -> {
                    attempts.incrementAndGet();
                    return Flux.concat(Flux.just("partial"), Flux.error(new IllegalStateException("boom")));
                }).doOnNext(received::add).blockLast(Duration.ofSeconds(5)));

        assertSame(IllegalStateException.class, error.getClass());
        assertEquals(1, attempts.get(), "已经输出过内容就不能重试，否则用户会看到重复代码");
        assertEquals(List.of("partial"), received);
    }

    @Test
    void doesNotRetryDeterministicBusinessErrors() {
        AiRetryGuard guard = guard(3);
        AtomicInteger attempts = new AtomicInteger();

        BusinessException error = assertThrows(BusinessException.class,
                () -> guard.guardStream("html", () -> {
                    attempts.incrementAndGet();
                    return Flux.error(new BusinessException(ErrorCode.PARAMS_ERROR, "参数错误"));
                }).blockLast(Duration.ofSeconds(5)));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
        assertEquals(1, attempts.get());
    }

    @Test
    void callRetriesTransientErrorsAndStopsAfterMaxAttempts() {
        AiRetryGuard guard = guard(3);
        AtomicInteger attempts = new AtomicInteger();

        String result = guard.call("routing", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new HttpException(503, "unavailable");
            }
            return "vue_project";
        });

        assertEquals("vue_project", result);
        assertEquals(3, attempts.get());

        AtomicInteger exhausted = new AtomicInteger();
        HttpException finalError = assertThrows(HttpException.class,
                () -> guard.call("routing", () -> {
                    exhausted.incrementAndGet();
                    throw new HttpException(503, "still unavailable");
                }));
        assertEquals(503, finalError.statusCode());
        assertEquals(3, exhausted.get());
    }

    @Test
    void callDoesNotRetryNonRetryableErrors() {
        AiRetryGuard guard = guard(3);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(BusinessException.class, () -> guard.call("routing", () -> {
            attempts.incrementAndGet();
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void retryabilityMatrixCoversTransportAndGuardrailErrors() {
        AiRetryGuard guard = guard(2);

        assertTrue(guard.isRetryable(new java.net.http.HttpTimeoutException("timeout")));
        assertTrue(guard.isRetryable(new java.net.ConnectException("refused")));
        assertTrue(guard.isRetryable(new HttpException(429, "rate limited")));
        assertTrue(guard.isRetryable(new HttpException(500, "server error")));
        assertTrue(guard.isRetryable(new dev.langchain4j.exception.RateLimitException("slow down")));

        assertFalse(guard.isRetryable(new HttpException(400, "bad request")));
        assertFalse(guard.isRetryable(new HttpException(401, "unauthorized")));
        assertFalse(guard.isRetryable(new BusinessException(ErrorCode.PARAMS_ERROR)));
        assertFalse(guard.isRetryable(new IllegalArgumentException("bad argument")));
        assertFalse(guard.isRetryable(new ToolCallGuard.ToolCallLimitException("tool loop")));
    }

    @Test
    void disabledGuardPassesThroughWithoutRetry() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setEnabled(false);
        AiRetryGuard guard = new AiRetryGuard(properties);
        AtomicInteger attempts = new AtomicInteger();

        // blockLast 会把信号里的受检异常包成 ReactiveException，断言原始原因即可。
        Throwable error = assertThrows(RuntimeException.class, () -> guard.guardStream("html", () -> {
            attempts.incrementAndGet();
            return Flux.error(new IOException("down"));
        }).blockLast(Duration.ofSeconds(5)));
        assertInstanceOf(IOException.class, error.getCause() == null ? error : error.getCause());
        assertEquals(1, attempts.get());
    }

    private static AiRetryGuard guard(int maxAttempts) {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setEnabled(true);
        properties.setMaxAttempts(maxAttempts);
        // 测试里把退避压到 1ms，避免为了验证重试次数而真实等待。
        properties.setInitialBackoff(Duration.ofMillis(1));
        properties.setMaxBackoff(Duration.ofMillis(2));
        properties.setJitter(0);
        return new AiRetryGuard(properties);
    }
}
