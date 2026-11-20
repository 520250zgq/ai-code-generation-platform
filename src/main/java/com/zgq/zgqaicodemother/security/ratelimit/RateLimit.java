package com.zgq.zgqaicodemother.security.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * AI 对话接口限流规则。
 *
 * <p>AI 接口的单次调用成本远高于普通查询，除了并发控制，还需要限制单个用户或来源 IP
 * 在时间窗口内的请求次数，避免脚本刷接口、共享账号被滥用或异常客户端疯狂重试。
 * 注解可以重复使用，因此同一个接口可以同时配置“按用户较宽松、按 IP 较严格”等多条规则；
 * 任意一条规则拒绝，请求就会被拒绝。</p>
 *
 * <p>{@link #key()} 是一次限流规则的命名空间，必须由开发者指定且保持稳定。它最终会进入
 * Redis key，不能拼接用户输入或包含敏感信息；切面会为它补充用户 id / IP 维度。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimit.List.class)
public @interface RateLimit {

    /**
     * 规则名称，用于隔离同一接口上的不同限流规则以及不同接口。建议使用稳定的短字符串，
     * 例如 {@code ai-chat-message}；该值不能为空。
     *
     * @return 限流规则名称
     */
    String key();

    /**
     * 一个窗口内允许通过的请求数。小于 1 时由限流器按 1 处理，避免配置错误导致接口永久不可用。
     *
     * @return 窗口内允许的请求数
     */
    int permits() default 10;

    /**
     * 固定窗口长度，单位为秒。小于 1 时由限流器按 1 秒处理。
     *
     * @return 窗口秒数
     */
    long windowSeconds() default 60;

    /**
     * 是否按登录用户维度限流。默认开启，防止单个账号在多台设备上绕过限制。
     * 未登录请求没有用户维度，只会退化为按 IP 限流。
     *
     * @return 是否按用户限流
     */
    boolean byUser() default true;

    /**
     * 是否按客户端 IP 维度限流。默认开启，防止匿名请求或批量注册账号绕过限制。
     * 只有应用部署在可信反向代理之后时才应信任转发头，具体由配置项
     * {@code trustedProxyHeader} 控制。
     *
     * @return 是否按 IP 限流
     */
    boolean byIp() default true;

    /**
     * 超限时返回给客户端的提示。留空时由切面使用通用提示。
     *
     * @return 限流提示语
     */
    String message() default "";

    /**
     * 重复 {@link RateLimit} 注解的容器。
     *
     * <p>Java 的 {@code @Repeatable} 必须借助容器注解保存多条规则；容器只用于反射读取，
     * 业务代码仍可直接在同一个方法上写多个 {@code @RateLimit}。</p>
     */
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {

        /**
         * 同一个方法或类上声明的全部限流规则。
         *
         * @return 限流规则数组
         */
        RateLimit[] value();
    }
}
