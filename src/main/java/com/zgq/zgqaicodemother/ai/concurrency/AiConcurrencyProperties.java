package com.zgq.zgqaicodemother.ai.concurrency;

import lombok.Data;

import java.time.Duration;

/**
 * AI 生成并发控制配置。
 *
 * <p>模型调用是长耗时 IO：一次 HTML 生成通常十几秒，Vue 工具调用链可能持续数分钟。
 * 如果不设上限，突发流量会把线程、模型配额和后端连接池一起打满；如果只用一个全局锁，
 * 又会让所有用户排在同一条队列里。因此这里把“允许同时跑几轮”和“同一应用能否并行”
 * 拆成两个维度分别配置。</p>
 *
 * <p>生产调优建议：{@code max-concurrent-requests} 先按目标模型的并发配额设置
 * （例如供应商允许 20 QPS 时，生成类请求取 8~10 留有重试余量），再根据服务器 CPU、
 * 内存和数据库连接池上限微调。</p>
 */
@Data
public class AiConcurrencyProperties {

    /**
     * 是否启用并发控制。关闭后不限制并发，仅保留同一应用的互斥，
     * 适合本地调试；生产环境不建议关闭。
     */
    private boolean enabled = true;

    /**
     * 全局同时进行的 AI 生成轮数上限。
     *
     * <p>必须是正数：配置为 0 或负数时，{@link AiConcurrencyLimiter} 会退化为 1，
     * 避免误配置导致所有请求被永久拒绝。</p>
     */
    private int maxConcurrentRequests = 8;

    /**
     * 等待空闲并发许可的最长时间。超时后返回“服务繁忙”，而不是让请求无限挂起。
     *
     * <p>取值要小于网关/前端的请求超时，否则用户会先看到连接超时而不是可读的业务提示。</p>
     */
    private Duration acquireTimeout = Duration.ofSeconds(30);

    /**
     * 等待同一应用上一轮生成结束的最长时间。
     *
     * <p>同一个应用共享一份对话记忆和同一个工程目录，两轮并发生成会互相覆盖文件、
     * 打乱上下文，因此必须互斥。这里默认只等 5 秒：同一应用连续点击两次时快速失败并提示
     * “正在生成中”，比让用户干等 30 秒更符合预期。</p>
     */
    private Duration perAppAcquireTimeout = Duration.ofSeconds(5);
}
