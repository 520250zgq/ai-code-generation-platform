package com.zgq.zgqaicodemother.ai.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 容错配置绑定。
 */
@Configuration
public class AiResilienceConfig {

    /**
     * 绑定 app.ai.retry 下的重试护轨配置。
     *
     * @return 重试配置
     */
    @Bean
    @ConfigurationProperties(prefix = "app.ai.retry")
    public AiRetryProperties aiRetryProperties() {
        return new AiRetryProperties();
    }

    /**
     * 重试护轨：只在安全窗口内对可恢复错误做有界重试。
     *
     * @param properties 重试配置
     * @return 重试护轨
     */
    @Bean
    public AiRetryGuard aiRetryGuard(AiRetryProperties properties) {
        return new AiRetryGuard(properties);
    }
}
