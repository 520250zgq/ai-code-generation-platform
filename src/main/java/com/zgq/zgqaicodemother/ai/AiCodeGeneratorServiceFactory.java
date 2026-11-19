package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.memory.ConversationMemoryCache;
import com.zgq.zgqaicodemother.ai.tools.ToolManager;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 代码生成服务工厂。
 *
 * <p>不同生成模式对模型、工具和对话记忆的要求不同：HTML / 多文件模式依赖 JSON 输出，
 * Vue 工程模式依赖工具调用和更强的推理模型。因此不能全局复用一个
 * {@link AiCodeGeneratorService}，而是按 {@code appId + codeGenType} 缓存服务代理。
 * 同一应用的同一种模式复用代理，不同模式互不干扰。</p>
 *
 * <p>所有服务都通过 {@link ChatMemoryProvider} 按 memoryId 获取对话记忆。这样即使
 * 缓存中的服务实例被淘汰，会话上下文仍然保存在 {@link ConversationMemoryCache} 中，
 * 下一次创建代理时可以继续复用。</p>
 */
@Component
public class AiCodeGeneratorServiceFactory {

    /**
     * 最多缓存的 AI Service 数量。每个应用最多缓存三种模式，超出后按最近最少使用淘汰。
     * 淘汰只丢弃代理对象，不删除对话记忆。
     */
    static final int MAX_CACHED_SERVICES = 500;

    /**
     * 单轮对话允许的连续工具调用次数上限。
     *
     * <p>LangChain4j 1.1.0 的流式工具调用不会应用该限制，流式路径还会在
     * {@code AiCodeGeneratorFacade} 中单独计数兜底，避免模型因幻觉无限调用工具。</p>
     */
    static final int MAX_SEQUENTIAL_TOOL_INVOCATIONS = 20;

    /**
     * 工作流单次生成的内存记忆窗口。工作流本身不需要多轮上下文，窗口只用于承载
     * 一次生成中的工具调用消息，避免无限增长。
     */
    static final int MAX_WORKFLOW_MEMORY_MESSAGES = 100;

    private final ChatModel chatModel;

    private final StreamingChatModel openAiStreamingChatModel;

    private final StreamingChatModel reasoningStreamingChatModel;

    private final ConversationMemoryCache conversationMemoryCache;

    private final ToolManager toolManager;

    /**
     * accessOrder=true 让 get 也更新访问顺序，实现按最近使用淘汰的 LRU 缓存。
     * 服务的创建包含 AiServices 代理构建，因此读写都在同一把锁内完成。
     */
    private final Map<String, AiCodeGeneratorService> serviceCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AiCodeGeneratorService> eldest) {
                    return size() > MAX_CACHED_SERVICES;
                }
            });

    public AiCodeGeneratorServiceFactory(
            @Qualifier("chatModel") ChatModel chatModel,
            @Qualifier("openAiStreamingChatModel") StreamingChatModel openAiStreamingChatModel,
            @Qualifier("reasoningStreamingChatModel") StreamingChatModel reasoningStreamingChatModel,
            ConversationMemoryCache conversationMemoryCache,
            ToolManager toolManager) {
        this.chatModel = chatModel;
        this.openAiStreamingChatModel = openAiStreamingChatModel;
        this.reasoningStreamingChatModel = reasoningStreamingChatModel;
        this.conversationMemoryCache = conversationMemoryCache;
        this.toolManager = toolManager;
    }

    /**
     * 获取指定应用、指定生成模式的 AI Service。
     *
     * @param appId       应用 id，同时作为对话记忆 id
     * @param codeGenType 代码生成类型
     * @return 可复用的 AI Service 代理
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(Long appId, CodeGenTypeEnum codeGenType) {
        validateRequest(appId, codeGenType);
        String cacheKey = buildCacheKey(appId, codeGenType);
        synchronized (serviceCache) {
            AiCodeGeneratorService service = serviceCache.get(cacheKey);
            if (service == null) {
                service = createAiCodeGeneratorService(codeGenType);
                serviceCache.put(cacheKey, service);
            }
            return service;
        }
    }

    /**
     * 获取工作流专用的 AI Service。
     *
     * <p>工作流与普通聊天是两条独立的生成链路：普通聊天需要 Redis 对话记忆支持多轮修改，
     * 而工作流每次都是一次性生成，不应把历史 HTML 响应重新塞回上下文。这里为单次调用
     * 构建一个只存在于内存中的短窗口记忆，既不读取也不写入应用的 Redis/数据库记忆，
     * 避免长对话把工作流输入撑得过大、甚至导致模型流长时间不返回。</p>
     *
     * @param appId       应用 id，会注入文件工具用于定位工程目录
     * @param codeGenType 代码生成类型
     * @return 每次调用独立的新 AI Service 代理
     */
    public AiCodeGeneratorService getWorkflowAiCodeGeneratorService(Long appId, CodeGenTypeEnum codeGenType) {
        validateRequest(appId, codeGenType);
        Map<Object, ChatMemory> memories = new java.util.concurrent.ConcurrentHashMap<>();
        ChatMemoryProvider isolatedProvider = memoryId -> memories.computeIfAbsent(memoryId, id ->
                MessageWindowChatMemory.builder()
                        .id(id)
                        .maxMessages(MAX_WORKFLOW_MEMORY_MESSAGES)
                        .build());
        return createAiCodeGeneratorService(codeGenType, isolatedProvider);
    }

    /**
     * 缓存键必须同时包含 appId 和 codeGenType：同一应用在不同模式下使用不同的模型、
     * 工具和流式返回类型，不能复用同一个代理。
     *
     * @param appId       应用 id
     * @param codeGenType 代码生成类型
     * @return 缓存键
     */
    static String buildCacheKey(Long appId, CodeGenTypeEnum codeGenType) {
        return appId + ":" + codeGenType.getValue();
    }

    /**
     * 按生成类型选择模型和工具配置并构建代理。
     *
     * @param codeGenType 代码生成类型
     * @return 新的 AI Service 代理
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(CodeGenTypeEnum codeGenType) {
        // 每个代理都使用 provider：调用时按 @MemoryId 找到对应应用的记忆窗口。
        ChatMemoryProvider memoryProvider = memoryId -> conversationMemoryCache.get(asAppId(memoryId));
        return createAiCodeGeneratorService(codeGenType, memoryProvider);
    }

    /**
     * 校验应用 id 和生成类型，普通代理与工作流代理共用。
     */
    private static void validateRequest(Long appId, CodeGenTypeEnum codeGenType) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        }
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
    }

    /**
     * 使用指定记忆提供者构建 AI Service。
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(CodeGenTypeEnum codeGenType,
                                                                 ChatMemoryProvider memoryProvider) {
        AiServices<AiCodeGeneratorService> builder = AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingModelFor(codeGenType))
                .chatMemoryProvider(memoryProvider);
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
            // 工具由 ToolManager 统一注册：创建使用 writeFile，增量修改使用 readDir / readFile /
            // modifyFile / deleteFile。工厂只负责把策略集合绑定给模型，不感知具体工具类型。
            builder.tools((Object[]) toolManager.getTools())
                    // 模型可能编造不存在的工具名。返回可读错误而不是抛出异常，让模型自行纠正。
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                            toolExecutionRequest,
                            "工具调用错误：不存在名为 \"" + toolExecutionRequest.name()
                                    + "\" 的工具。只能使用以下工具：" + toolManager.getToolNames()
                                    + "，请修正工具名后重试。"))
                    // 非流式路径由框架限制连续工具调用次数；流式路径由 Facade 兜底限制。
                    .maxSequentialToolsInvocations(MAX_SEQUENTIAL_TOOL_INVOCATIONS);
        }
        return builder.build();
    }

    /**
     * 根据生成类型选择流式模型。
     *
     * <p>HTML / 多文件模式需要稳定的 JSON 输出，使用配置了 response-format 的通用流式模型；
     * Vue 工程模式需要多轮工具调用，使用独立的推理模型配置（默认关闭 JSON 输出）。</p>
     *
     * @param codeGenType 代码生成类型
     * @return 流式模型
     */
    private StreamingChatModel streamingModelFor(CodeGenTypeEnum codeGenType) {
        return switch (codeGenType) {
            case HTML, MULTI_FILE -> openAiStreamingChatModel;
            case VUE_PROJECT -> reasoningStreamingChatModel;
        };
    }

    /**
     * 工具方法注入的 memoryId 必须是应用 id；转换为 Long 以便定位工程目录和记忆窗口。
     */
    private static Long asAppId(Object memoryId) {
        if (memoryId instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("对话记忆 id 必须是应用 id 数字，实际为：" + memoryId);
    }
}
