package com.zgq.zgqaicodemother.ai.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 工具调用保护配置绑定。
 */
@Configuration
public class ToolCallConfig {

    /**
     * 绑定 app.ai.tools 下的工具调用保护配置。
     *
     * @return 工具调用保护配置
     */
    @Bean
    @ConfigurationProperties(prefix = "app.ai.tools")
    public ToolCallProperties toolCallProperties() {
        return new ToolCallProperties();
    }

    /**
     * 工具调用循环保护器，供全部文件工具和流式处理器共用。
     *
     * @param properties 工具调用保护配置
     * @return 保护器实例
     */
    @Bean
    public ToolCallGuard toolCallGuard(ToolCallProperties properties) {
        return new ToolCallGuard(properties);
    }
}
