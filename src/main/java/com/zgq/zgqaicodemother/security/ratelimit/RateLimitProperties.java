package com.zgq.zgqaicodemother.security.ratelimit;

import lombok.Data;

import java.time.Duration;

/**
 * AI 接口限流配置。
 *
 * <p>限流属于辅助能力，不应该成为 AI 对话链路的单点故障。生产环境需要根据 Redis 的
 * 可用性选择降级策略：Redis 正常时所有实例共享计数；Redis 故障时要么退回单机内存继续
 * 保护接口，要么直接放行以保证业务可用。两种取舍都在下方字段中显式配置，避免代码里写死。</p>
 */
@Data
public class RateLimitProperties {

    /**
     * 是否启用接口限流。关闭后切面会在读取到规则后直接放行，不会访问 Redis 或登录信息，
     * 便于本地调试或线上紧急止损；不要让该开关替代正常的容量评估。
     */
    private boolean enabled = true;

    /**
     * Redis 不可用时的降级策略。
     *
     * <p>{@code true}：切换到本地内存计数继续限流，宁可承担多实例之间计数不共享的精度损失，
     * 也不让 AI 接口完全失去保护；{@code false}：直接放行，避免限流组件自身故障阻断正常的
     * AI 对话业务。后者是“宁可放行也不阻断业务”的 deny-open 语义，需要结合业务风险选择。</p>
     */
    private boolean failOpen = true;

    /**
     * 本地兜底窗口最多保存的 key 数量。Redis 故障时每个用户/IP 都会生成一个窗口，如果
     * 没有上限，攻击流量会把 JVM 内存打满；超过上限后会先清理过期窗口，再按近似插入顺序
     * 淘汰最旧的窗口。生产环境可根据实例内存调整，但不应设置为 0。
     */
    private int maxTrackedKeys = 100000;

    /**
     * 本地兜底窗口的定期清理间隔。清理只在请求线程内顺带执行，不额外创建后台线程；
     * 间隔太短会增加遍历成本，太长会让已过期窗口在内存中多停留一段时间。
     */
    private Duration localWindowCleanupInterval = Duration.ofMinutes(1);

    /**
     * Redis 限流 key 的全局前缀，用于区分应用、环境和其它使用 Redis 的模块。
     * 多实例必须使用同一份值才能共享计数；本地调试可以使用不同前缀隔离数据。
     */
    private String keyPrefix = "zgq:ai-code-mother:rate-limit:";

    /**
     * 可信反向代理写入客户端真实 IP 的请求头名称，默认 {@code X-Forwarded-For}。
     *
     * <p>只有应用部署在会覆盖或重写该请求头的可信代理之后时才应信任它；如果应用直接暴露
     * 给公网，客户端可以伪造这个头。解析器仍会清洗端口、长度和非法字符，但这不能替代
     * 网关层的可信来源校验。</p>
     */
    private String trustedProxyHeader = "X-Forwarded-For";
}
