package com.zgq.zgqaicodemother.ai.resilience;

import cn.hutool.core.util.StrUtil;
import com.zgq.zgqaicodemother.ai.tools.ToolCallGuard;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import dev.langchain4j.exception.HttpException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * AI 调用的重试护轨。
 *
 * <p>“护轨”体现在三条约束上：</p>
 * <ol>
 *   <li><b>不重复输出</b>：流式生成一旦向客户端发出过内容，就不再重试。否则用户会看到
 *       两段重复的页面代码，而解析器也会把两段内容拼成一个非法 JSON。</li>
 *   <li><b>不掩盖业务错误</b>：参数错误、权限错误、提示词审查拒绝、工具循环熔断都是确定性结果，
 *       重试只会放大成本，直接抛出。</li>
 *   <li><b>有界退避</b>：指数退避 + 抖动 + 最大退避时间，避免多实例同时重试把供应商打崩。</li>
 * </ol>
 *
 * <p>注意：Vue 工程模式不经过本类重试。该模式由模型调用文件工具写盘，重试意味着重复执行
 * 有副作用的写操作，收益远小于风险。</p>
 */
@Slf4j
public class AiRetryGuard {

    /**
     * 判断异常原因时向下追溯的最大层数，避免异常链成环导致死循环。
     */
    private static final int MAX_CAUSE_DEPTH = 4;

    /**
     * 重试日志与错误提示中保留的最大异常信息长度，避免把模型输出写进日志。
     */
    private static final int MAX_SUMMARY_LENGTH = 200;

    private final AiRetryProperties properties;

    /**
     * 最大尝试次数，构造时归一化，避免配置 0 或负数导致流直接不执行。
     */
    private final int maxAttempts;

    public AiRetryGuard(AiRetryProperties properties) {
        this.properties = Objects.requireNonNull(properties, "重试配置不能为空");
        this.maxAttempts = Math.max(1, properties.getMaxAttempts());
    }

    /**
     * 包裹一段流式调用，在“尚未向客户端输出任何内容”的前提下做有界重试。
     *
     * <p>{@code streamFactory} 会被延迟调用，每次重试都重新创建一条全新的模型流；
     * 调用方必须保证工厂方法返回的是冷流（订阅时才发起请求）。</p>
     *
     * @param operation     操作名，只用于日志和监控
     * @param streamFactory 创建模型流的方法
     * @param <T>           流元素类型
     * @return 带重试护轨的流
     */
    public <T> Flux<T> guardStream(String operation, Supplier<Flux<T>> streamFactory) {
        Objects.requireNonNull(streamFactory, "流工厂不能为空");
        if (!properties.isEnabled() || maxAttempts <= 1) {
            return Flux.defer(streamFactory::get);
        }
        return Flux.defer(() -> {
            // 每次订阅独立记录“是否已经输出过内容”，避免多次订阅共享状态。
            AtomicBoolean emitted = new AtomicBoolean(false);
            return Flux.defer(streamFactory::get)
                    .doOnNext(ignored -> emitted.set(true))
                    .retryWhen(buildRetrySpec(operation)
                            .filter(error -> !emitted.get() && isRetryable(error))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        });
    }

    /**
     * 包裹一次同步调用并做有界重试，用于分类、摘要等幂等且低成本的模型请求。
     *
     * @param operation 操作名，只用于日志
     * @param call      实际调用
     * @param <T>       返回类型
     * @return 调用结果
     */
    public <T> T call(String operation, Supplier<T> call) {
        Objects.requireNonNull(call, "调用不能为空");
        if (!properties.isEnabled() || maxAttempts <= 1) {
            return call.get();
        }
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException error) {
                if (!isRetryable(error)) {
                    throw error;
                }
                lastError = error;
                if (attempt >= maxAttempts) {
                    break;
                }
                Duration backoff = backoffFor(attempt);
                log.warn("AI 调用失败，准备重试，operation={}，第 {}/{} 次，退避={}ms，原因={}",
                        operation, attempt, maxAttempts, backoff.toMillis(), summarize(error));
                sleep(backoff);
            }
        }
        throw lastError == null
                ? new IllegalStateException("AI 调用失败且没有捕获到异常")
                : lastError;
    }

    /**
     * 判断异常是否属于“可以安全重试”的瞬时故障。
     *
     * <p>白名单而不是黑名单：只有明确可恢复的网络、限流、服务端错误才重试，
     * 其余一律按确定性失败处理，避免把逻辑 bug 重试成多次副作用。</p>
     *
     * @param error 异常
     * @return 可重试返回 true
     */
    public boolean isRetryable(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof BusinessException
                    || current instanceof ToolCallGuard.ToolCallLimitException
                    || current instanceof IllegalArgumentException
                    || current instanceof InterruptedException) {
                // 确定性失败：参数、权限、审查拒绝、工具熔断重试也不会变好。
                return false;
            }
            if (current instanceof HttpException httpException) {
                int status = httpException.statusCode();
                // 408 超时、429 限流、5xx 服务端错误可以重试；其余 4xx 是请求本身的问题。
                return status == 408 || status == 429 || status >= 500;
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.startsWith("dev.langchain4j.guardrail")) {
                // 护轨失败说明模型连续输出不合格内容，重试只会重复消耗 token。
                return false;
            }
            if (className.endsWith("RateLimitException")
                    || className.endsWith("InternalServerErrorException")
                    || className.endsWith("ServiceUnavailableException")
                    || className.endsWith("RetriableException")
                    || className.endsWith("TimeoutException")) {
                return true;
            }
            current = unwrap(current);
        }
        return false;
    }

    /**
     * 构造带指数退避与抖动的重试策略。
     */
    private RetryBackoffSpec buildRetrySpec(String operation) {
        return Retry.backoff(Math.max(0, maxAttempts - 1), positive(properties.getInitialBackoff()))
                .maxBackoff(positive(properties.getMaxBackoff()))
                .jitter(clampJitter(properties.getJitter()))
                .doBeforeRetry(signal -> log.warn("AI 流式调用失败，准备重试，operation={}，第 {} 次，原因={}",
                        operation, signal.totalRetries() + 1, summarize(signal.failure())));
    }

    /**
     * 第 attempt 次失败后的退避时间，带指数增长；实际等待由 Reactor 的重试策略完成。
     */
    private Duration backoffFor(int attempt) {
        long base = positive(properties.getInitialBackoff()).toMillis();
        long max = positive(properties.getMaxBackoff()).toMillis();
        long delay = Math.min(max, base * (1L << Math.min(attempt - 1, 10)));
        return Duration.ofMillis(Math.max(1L, delay));
    }

    /**
     * 拆解常见的包装异常，让底层网络异常能够被识别。
     */
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error.getCause();
        if (cause == null || cause == error) {
            return null;
        }
        // CompletionException / ExecutionException 只是包装层，真正的原因在 cause 上。
        if (error instanceof CompletionException || error instanceof ExecutionException) {
            return cause;
        }
        return cause;
    }

    /**
     * 中断等待：恢复中断标记并放弃本次重试，把控制权交还给上层。
     */
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_BUSY, "AI 调用重试被中断，请稍后重试");
        }
    }

    private static Duration positive(Duration value) {
        return value == null || value.isNegative() || value.isZero() ? Duration.ofMillis(1) : value;
    }

    private static double clampJitter(double jitter) {
        if (Double.isNaN(jitter) || jitter < 0) {
            return 0.0;
        }
        return Math.min(1.0, jitter);
    }

    /**
     * 日志里只保留异常类型和截断后的消息，避免把模型返回的大段内容写进日志。
     */
    private static String summarize(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = StrUtil.maxLength(StrUtil.blankToDefault(error.getMessage(), ""), MAX_SUMMARY_LENGTH);
        return error.getClass().getSimpleName() + (StrUtil.isEmpty(message) ? "" : ("：" + message));
    }
}
