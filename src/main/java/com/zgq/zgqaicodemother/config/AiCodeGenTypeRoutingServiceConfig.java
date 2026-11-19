package com.zgq.zgqaicodemother.config;

import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 智能路由 AI Service 配置。
 *
 * <p>路由只输出一个枚举值，使用独立的 {@code routingChatModel}，与代码生成使用的模型彻底分离。
 * 这样可以选择更小、更便宜、延迟更低的分类模型，也不会被代码生成模型上的
 * {@code response-format} JSON 约束干扰。</p>
 *
 * <p>AiServices 代理本身不保存请求状态，可以作为单例复用；线程安全由底层的 ChatModel 保证。</p>
 */
@Configuration
public class AiCodeGenTypeRoutingServiceConfig {

    /**
     * 创建路由 AI Service。
     *
     * @param routingChatModel 专用于分类的路由模型
     * @return 可直接注入使用的路由服务代理
     */
    @Bean
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService(
            @Qualifier("routingChatModel") ChatModel routingChatModel) {
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(routingChatModel)
                .build();
    }
}
