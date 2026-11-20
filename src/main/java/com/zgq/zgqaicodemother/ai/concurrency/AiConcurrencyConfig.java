package com.zgq.zgqaicodemother.ai.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 并发控制配置绑定。
 *
 * <p>使用独立的 {@code app.ai.concurrency} 前缀，与工作流自身的 {@code app.workflow.max-concurrent-runs}
 * 区分：前者限制“普通对话生成”，后者限制“一次性工作流任务”，两者可以独立调优。</p>
 */
@Configuration
public class AiConcurrencyConfig {

    /**
     * 绑定 app.ai.concurrency 下的并发配置。
     *
     * @return 并发配置对象
     */
    @Bean
    @ConfigurationProperties(prefix = "app.ai.concurrency")
    public AiConcurrencyProperties aiConcurrencyProperties() {
        return new AiConcurrencyProperties();
    }

    /**
     * AI 生成并发控制器，供对话生成和需要应用级互斥的链路复用。
     *
     * @param properties 并发配置
     * @return 并发控制器
     */
    @Bean
    public AiConcurrencyLimiter aiConcurrencyLimiter(AiConcurrencyProperties properties) {
        return new AiConcurrencyLimiter(properties);
    }
}
