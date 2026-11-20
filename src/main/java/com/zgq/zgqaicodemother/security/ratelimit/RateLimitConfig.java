package com.zgq.zgqaicodemother.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 接口限流配置绑定。
 *
 * <p>沿用项目现有的“独立配置类 + {@code @ConfigurationProperties} 方法”写法，把
 * {@code app.security.rate-limit} 单独绑定为 Bean。限流器通过构造器注入该配置，
 * 这样测试可以传入自定义对象而不依赖 Spring 容器。此配置类不创建限流器本身，
 * 限流器使用 {@code @Component} 自动装配，避免同一类型出现两个 Bean。</p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * 绑定 app.security.rate-limit 下的接口限流配置。
     *
     * @return 限流配置对象
     */
    @Bean
    @ConfigurationProperties(prefix = "app.security.rate-limit")
    public RateLimitProperties rateLimitProperties() {
        return new RateLimitProperties();
    }
}
