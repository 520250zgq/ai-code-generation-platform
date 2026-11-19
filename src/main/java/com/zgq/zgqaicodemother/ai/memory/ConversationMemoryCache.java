package com.zgq.zgqaicodemother.ai.memory;

import com.zgq.zgqaicodemother.constant.ChatHistoryConstant;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对话记忆的本地热点缓存。
 *
 * <p>每个应用对应一个 {@link ChatMemory}。第一次访问时优先从 Redis 恢复；Redis 未命中
 * （首次对话、过期或实例刚重启）时，从 MySQL 的 chat_history 回源最近若干条消息，
 * 让 AI 在服务重启后仍能理解上下文。</p>
 *
 * <p>本地缓存按最近最少使用淘汰，且淘汰不会删除 Redis 数据；下次访问会重新从 Redis 恢复。
 * 应用被删除时调用 {@link #evict(Long)} 同时清理本地缓存和 Redis key。</p>
 */
@Slf4j
public class ConversationMemoryCache {

    private final ChatMemoryStore chatMemoryStore;

    private final ChatHistoryService chatHistoryService;

    private final int maxMessages;

    private final int maxCachedConversations;

    /**
     * accessOrder=true 的 LinkedHashMap 实现 LRU；所有访问都在同一把锁内，保证
     * computeIfAbsent 的初始化和淘汰是原子的。
     */
    private final Map<Long, ChatMemory> memories;

    public ConversationMemoryCache(ChatMemoryStore chatMemoryStore,
                                   ChatHistoryService chatHistoryService,
                                   ChatMemoryProperties properties) {
        Assert.notNull(chatMemoryStore, "chatMemoryStore 不能为空");
        Assert.notNull(chatHistoryService, "chatHistoryService 不能为空");
        Assert.notNull(properties, "对话记忆配置不能为空");
        Assert.isTrue(properties.getMaxMessages() > 0, "对话记忆窗口大小必须大于 0");
        Assert.isTrue(properties.getMaxCachedConversations() > 0, "本地会话缓存数量必须大于 0");
        this.chatMemoryStore = chatMemoryStore;
        this.chatHistoryService = chatHistoryService;
        this.maxMessages = properties.getMaxMessages();
        this.maxCachedConversations = properties.getMaxCachedConversations();
        this.memories = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, ChatMemory> eldest) {
                return size() > maxCachedConversations;
            }
        };
    }

    /**
     * 获取应用的对话记忆，必要时从 Redis 或 MySQL 初始化。
     *
     * <p>调用方应在保存本轮用户消息之前调用本方法，否则回源时可能把本轮消息重复加入记忆。
     * 应用内聊天流程已由 AppServiceImpl 保证这一顺序。</p>
     */
    public ChatMemory get(Long appId) {
        Assert.notNull(appId, "appId 不能为空");
        synchronized (memories) {
            return memories.computeIfAbsent(appId, this::createMemory);
        }
    }

    /**
     * 删除应用的本地记忆和 Redis 记忆；用于应用删除等需要彻底清理的场景。
     */
    public void evict(Long appId) {
        if (appId == null) {
            return;
        }
        synchronized (memories) {
            memories.remove(appId);
        }
        try {
            chatMemoryStore.deleteMessages(appId);
        } catch (RuntimeException e) {
            // 缓存清理失败不应阻断业务删除；Redis key 仍会按 TTL 自然过期。
            log.warn("清理对话记忆失败，appId={}", appId, e);
        }
    }

    /**
     * 当前缓存的会话数量，供监控和测试使用。
     */
    public int cachedCount() {
        synchronized (memories) {
            return memories.size();
        }
    }

    /**
     * 构建消息窗口：先读取 Redis；为空时再从数据库加载最近历史。
     */
    private ChatMemory createMemory(Long appId) {
        MessageWindowChatMemory memory = MessageWindowChatMemory.builder()
                .id(appId)
                .chatMemoryStore(chatMemoryStore)
                .maxMessages(maxMessages)
                .build();
        if (!memory.messages().isEmpty()) {
            return memory;
        }
        List<ChatHistory> history = chatHistoryService.listRecentMessages(
                appId, Math.min(maxMessages, ChatHistoryConstant.MAX_MEMORY_HISTORY_SIZE));
        List<ChatMessage> restored = history.stream()
                .filter(Objects::nonNull)
                .map(this::toChatMessage)
                .filter(Objects::nonNull)
                .toList();
        if (!restored.isEmpty()) {
            memory.add(restored);
            log.info("已从数据库恢复对话记忆，appId={}，消息数={}", appId, restored.size());
        }
        return memory;
    }

    /**
     * 把数据库消息转换为 LangChain4j 消息；失败的 AI 占位消息不进入上下文。
     */
    private ChatMessage toChatMessage(ChatHistory history) {
        ChatHistoryMessageTypeEnum type = ChatHistoryMessageTypeEnum.getEnumByValue(history.getMessageType());
        if (type == ChatHistoryMessageTypeEnum.USER) {
            return UserMessage.from(history.getMessage());
        }
        if (type == ChatHistoryMessageTypeEnum.AI
                && !history.getMessage().startsWith(ChatHistoryConstant.AI_FAILURE_MESSAGE_PREFIX)) {
            return AiMessage.from(history.getMessage());
        }
        return null;
    }
}
