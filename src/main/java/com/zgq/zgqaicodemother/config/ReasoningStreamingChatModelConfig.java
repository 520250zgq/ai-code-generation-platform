package com.zgq.zgqaicodemother.config;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Vue 工程生成使用的推理流式模型配置。
 *
 * <p>Vue 模式需要多轮工具调用，和 HTML / 多文件模式强调的 JSON 输出不同，因此单独配置：
 * 不设置 {@code response-format}，允许模型返回 tool_calls；输出上限独立配置，便于按模型调整。</p>
 *
 * <p>连接信息默认复用 {@code streaming-chat-model} 的基地址和密钥，避免维护两份重复配置；
 * 需要切换到推理更强的模型时，只需覆盖 {@code reasoning-streaming-chat-model.model-name}，
 * 例如 {@code deepseek-reasoner}。</p>
 */
@Configuration
public class ReasoningStreamingChatModelConfig {

    /**
     * 推理流式模型（用于 Vue 项目生成，带工具调用）。
     */
    @Bean
    public StreamingChatModel reasoningStreamingChatModel(
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.base-url:${langchain4j.open-ai.streaming-chat-model.base-url:https://api.openai.com/v1}}")
            String baseUrl,
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.api-key:${langchain4j.open-ai.streaming-chat-model.api-key}}")
            String apiKey,
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.model-name:deepseek-chat}") String modelName,
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.log-responses:false}") boolean logResponses,
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.timeout:180s}") Duration timeout,
            @Value("${langchain4j.open-ai.reasoning-streaming-chat-model.max-tokens:8192}") int maxTokens) {
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(JdkHttpClient.builder())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .timeout(timeout)
                .maxTokens(maxTokens)
                .build();
    }
}
