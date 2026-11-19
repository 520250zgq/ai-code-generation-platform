package com.zgq.zgqaicodemother.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工作流配置绑定。
 *
 * <p>使用独立的 {@code app.workflow} 前缀，避免和原有 {@code app.cos}、{@code app.deploy}
 * 配置混在一起；密钥类配置全部通过环境变量或 application-local.yml 注入。</p>
 */
@Configuration
public class WorkflowConfig {

    /**
     * 绑定 app.workflow 下的工作流配置。
     *
     * @return 工作流配置对象
     */
    @Bean
    @ConfigurationProperties(prefix = "app.workflow")
    public WorkflowProperties workflowProperties() {
        return new WorkflowProperties();
    }
}
