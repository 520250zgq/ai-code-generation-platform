package com.zgq.zgqaicodemother.security.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prompt 审查配置。
 *
 * <p>使用独立的 {@code app.security.prompt-review} 前缀，避免和 {@code app.workflow}、
 * {@code app.cos} 等业务配置混在一起。配置类只负责绑定和装配，审查逻辑全部在
 * {@link PromptReviewService} 中，方便单元测试直接 new 出服务而不启动 Spring 容器。</p>
 */
@Configuration
public class PromptReviewConfig {

    /**
     * 绑定 app.security.prompt-review 下的审查配置。
     *
     * @return 审查配置对象
     */
    @Bean
    @ConfigurationProperties(prefix = "app.security.prompt-review")
    public PromptReviewProperties promptReviewProperties() {
        return new PromptReviewProperties();
    }

    /**
     * 创建 Prompt 审查服务。服务本身无状态，可以安全地被多个请求线程共享。
     *
     * @param promptReviewProperties 审查配置
     * @return Prompt 审查服务
     */
    @Bean
    public PromptReviewService promptReviewService(PromptReviewProperties promptReviewProperties) {
        return new PromptReviewService(promptReviewProperties);
    }
}
