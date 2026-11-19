package com.zgq.zgqaicodemother.config;

import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

/**
 * AI 模型 Bean 配置。
 *
 * <p>模型 Bean 从 {@code AiCodeGeneratorServiceFactory} 中拆分出来，避免“配置类构造
 * 自己定义的 Bean”造成循环依赖；工厂只负责按生成类型挑选模型并装配 AI Service。</p>
 */
@Configuration
public class AiModelConfig {

    /**
     * 创建同步对话模型，供非流式生成和结构化结果解析使用。
     */
    @Bean
    public ChatModel chatModel(
            @Value("${langchain4j.open-ai.chat-model.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.chat-model.log-responses:false}") boolean logResponses,
            @Value("${langchain4j.open-ai.chat-model.timeout:120s}") Duration timeout,
            @Value("${langchain4j.open-ai.chat-model.max-retries:2}") int maxRetries,
            @Value("${langchain4j.open-ai.chat-model.temperature:0.1}") double temperature,
            @Value("${langchain4j.open-ai.chat-model.max-tokens:16384}") int maxTokens,
            @Value("${langchain4j.open-ai.chat-model.response-format:json_object}") String responseFormat) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(JdkHttpClient.builder())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .responseFormat(responseFormat)
                .build();
    }

    /**
     * 创建智能路由专用对话模型。
     *
     * <p>路由决策只是一个简单的分类任务，不需要代码生成模型的能力。这里单独配置
     * {@code routing-chat-model}，生产环境可替换成更小、更便宜、输出更快的模型
     * （如 {@code gpt-4o-mini}、{@code qwen-turbo}），并通过较小的输出上限和较短的超时
     * 控制创建应用时的等待时间。</p>
     *
     * <p>注意不能为该模型设置 {@code response-format: json_object/json_schema}：路由服务直接返回
     * {@link com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum}，LangChain4j 会使用
     * “只能返回枚举值”的纯文本格式指令；开启 JSON 约束会改变输出格式，导致枚举解析失败。</p>
     */
    @Bean
    public ChatModel routingChatModel(
            @Value("${langchain4j.open-ai.routing-chat-model.base-url:${langchain4j.open-ai.streaming-chat-model.base-url:https://api.openai.com/v1}}")
            String baseUrl,
            @Value("${langchain4j.open-ai.routing-chat-model.api-key:${langchain4j.open-ai.streaming-chat-model.api-key}}")
            String apiKey,
            @Value("${langchain4j.open-ai.routing-chat-model.model-name:deepseek-chat}") String modelName,
            @Value("${langchain4j.open-ai.routing-chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.routing-chat-model.log-responses:false}") boolean logResponses,
            @Value("${langchain4j.open-ai.routing-chat-model.timeout:10s}") Duration timeout,
            @Value("${langchain4j.open-ai.routing-chat-model.max-retries:0}") int maxRetries,
            @Value("${langchain4j.open-ai.routing-chat-model.temperature:0.0}") double temperature,
            @Value("${langchain4j.open-ai.routing-chat-model.max-tokens:32}") int maxTokens) {
        return OpenAiChatModel.builder()
                .httpClientBuilder(JdkHttpClient.builder())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .timeout(timeout)
                .maxRetries(maxRetries)
                .temperature(temperature)
                .maxTokens(maxTokens)
                // 枚举返回值走纯文本格式指令，不向兼容 OpenAI 协议的供应商发送 json_schema。
                .supportedCapabilities(Set.of())
                .build();
    }

    /**
     * 创建流式对话模型，供 HTML / 多文件模式逐段返回代码。
     *
     * <p>Bean 名称固定为 {@code openAiStreamingChatModel}：应用中还有
     * {@code reasoningStreamingChatModel}，命名区分后既能避免 Bean 名称冲突，
     * 也便于在装配时用 {@code @Qualifier} 精确指定。</p>
     */
    @Bean
    public StreamingChatModel openAiStreamingChatModel(
            @Value("${langchain4j.open-ai.streaming-chat-model.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${langchain4j.open-ai.streaming-chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.streaming-chat-model.model-name}") String modelName,
            @Value("${langchain4j.open-ai.streaming-chat-model.log-requests:false}") boolean logRequests,
            @Value("${langchain4j.open-ai.streaming-chat-model.log-responses:false}") boolean logResponses,
            @Value("${langchain4j.open-ai.streaming-chat-model.timeout:120s}") Duration timeout,
            @Value("${langchain4j.open-ai.streaming-chat-model.temperature:0.1}") double temperature,
            @Value("${langchain4j.open-ai.streaming-chat-model.max-tokens:16384}") int maxTokens,
            @Value("${langchain4j.open-ai.streaming-chat-model.response-format:json_object}") String responseFormat) {
        return OpenAiStreamingChatModel.builder()
                .httpClientBuilder(JdkHttpClient.builder())
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .timeout(timeout)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .responseFormat(responseFormat)
                .build();
    }
}
