package com.zgq.zgqaicodemother.workflow.config;

import com.zgq.zgqaicodemother.workflow.ai.ImageCollectionPlanService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图片收集规划 AI Service 配置。
 *
 * <p>复用项目现有的 {@code chatModel}（JSON 输出模式），不需要新增模型客户端。图片规划失败时
 * 图片收集服务会退回关键词规则，因此这里不引入额外的重试和降级配置。</p>
 */
@Configuration
public class ImageCollectionPlanServiceConfig {

    /**
     * 创建图片收集规划服务。
     *
     * @param chatModel JSON 输出模式的对话模型
     * @return AI Service 代理
     */
    @Bean
    public ImageCollectionPlanService imageCollectionPlanService(
            @Qualifier("chatModel") ChatModel chatModel) {
        return AiServices.builder(ImageCollectionPlanService.class)
                .chatModel(chatModel)
                .build();
    }
}
