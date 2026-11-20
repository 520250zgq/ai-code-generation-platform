package com.zgq.zgqaicodemother.security.ratelimit;

/**
 * 一次限流判断的结果。
 *
 * <p>使用不可变 record 而不是直接返回布尔值，是为了让调用方同时拿到剩余额度和建议重试
 * 时间：切面可以把重试时间放入异常，后续接入的异常处理器可以直接生成 {@code Retry-After}，
 * 不需要再次访问 Redis。字段在构造时统一收敛，拒绝结果至少等待 1 秒，避免客户端立即重试
 * 形成无效流量。</p>
 *
 * @param allowed           是否允许本次请求
 * @param remaining         当前窗口剩余可用次数，拒绝时为 0
 * @param retryAfterSeconds 拒绝时建议等待的秒数，允许时为 0
 */
public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds) {

    /**
     * 规范化计数，防止调用方或降级路径传入负数造成误导。
     */
    public RateLimitDecision {
        remaining = Math.max(0L, remaining);
        retryAfterSeconds = allowed ? Math.max(0L, retryAfterSeconds) : Math.max(1L, retryAfterSeconds);
    }

    /**
     * 构造放行结果。
     *
     * @param remaining 当前窗口剩余可用次数
     * @return 放行结果
     */
    public static RateLimitDecision allowed(long remaining) {
        return new RateLimitDecision(true, remaining, 0L);
    }

    /**
     * 构造拒绝结果。
     *
     * @param retryAfterSeconds 建议等待秒数，小于 1 时按 1 处理
     * @return 拒绝结果
     */
    public static RateLimitDecision rejected(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0L, Math.max(1L, retryAfterSeconds));
    }
}
