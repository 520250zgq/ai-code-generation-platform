package com.zgq.zgqaicodemother.workflow.config;

import com.zgq.zgqaicodemother.workflow.ai.CodeQualityCheckService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 代码质检 AI Service 配置。
 *
 * <p>复用项目现有的 {@code chatModel}（JSON 输出模式），与图片收集计划一致，不需要新增
 * 模型客户端。模型超时或返回非法 JSON 时，质检节点会降级为“仅静态检查”，不会阻断工作流。</p>
 */
@Configuration
public class CodeQualityCheckServiceConfig {

    /**
     * 创建代码质检服务。
     *
     * @param chatModel JSON 输出模式的对话模型
     * @return AI Service 代理
     */
    @Bean
    public CodeQualityCheckService codeQualityCheckService(
            @Qualifier("chatModel") ChatModel chatModel) {
        return AiServices.builder(CodeQualityCheckService.class)
                .chatModel(chatModel)
                .build();
    }
}
