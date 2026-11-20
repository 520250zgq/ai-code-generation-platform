package com.zgq.zgqaicodemother.security.ratelimit;

import lombok.Getter;

/**
 * 请求触发接口限流时抛出的异常。
 *
 * <p>该异常携带命中的规则、维度以及建议的重试等待秒数，方便全局异常处理器把它转换为
 * HTTP 429 和 {@code Retry-After} 响应头；不应把它当作系统错误返回 500。异常本身不包含
 * 请求参数、用户输入或其它敏感信息。</p>
 */
@Getter
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "请求过于频繁，请稍后重试";

    /**
     * 命中的限流规则名称。
     */
    private final String rule;

    /**
     * 触发限流的维度，取值为 {@code user} 或 {@code ip}。
     */
    private final String dimension;

    /**
     * 建议客户端等待的秒数，始终至少为 1。
     */
    private final long retryAfterSeconds;

    /**
     * 使用通用提示语构造限流异常。
     *
     * @param rule              规则名称，允许为 null
     * @param dimension         限流维度，允许为 null
     * @param retryAfterSeconds 建议等待秒数，小于 1 时按 1 处理
     */
    public RateLimitExceededException(String rule, String dimension, long retryAfterSeconds) {
        this(DEFAULT_MESSAGE, rule, dimension, retryAfterSeconds);
    }

    /**
     * 使用自定义提示语构造限流异常。
     *
     * <p>所有字段都做兜底，避免异常处理器在限流这种高频路径上再因为空指针或非法等待时间
     * 产生二次错误。</p>
     *
     * @param message           提示语，空白时使用通用提示
     * @param rule              规则名称，允许为 null
     * @param dimension         限流维度，允许为 null
     * @param retryAfterSeconds 建议等待秒数，小于 1 时按 1 处理
     */
    public RateLimitExceededException(String message, String rule, String dimension, long retryAfterSeconds) {
        super(message == null || message.isBlank() ? DEFAULT_MESSAGE : message);
        this.rule = rule == null ? "" : rule;
        this.dimension = dimension == null ? "" : dimension;
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }
}
