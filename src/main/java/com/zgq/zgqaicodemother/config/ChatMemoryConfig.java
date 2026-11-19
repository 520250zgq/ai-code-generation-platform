package com.zgq.zgqaicodemother.config;

import com.zgq.zgqaicodemother.ai.memory.ChatMemoryProperties;
import com.zgq.zgqaicodemother.ai.memory.ConversationMemoryCache;
import com.zgq.zgqaicodemother.ai.memory.SpringRedisChatMemoryStore;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 对话记忆配置。
 *
 * <p>Redis 是对话记忆的持久化层，本地 {@link ConversationMemoryCache} 只缓存热点会话。
 * 每个 appId 一个记忆窗口，AI 轮次之间以及工具调用循环之间共享同一份上下文。</p>
 */
@Configuration
public class ChatMemoryConfig {

    /**
     * 绑定对话记忆配置。
     */
    @Bean
    @ConfigurationProperties(prefix = "app.chat-memory")
    public ChatMemoryProperties chatMemoryProperties() {
        return new ChatMemoryProperties();
    }

    /**
     * 对话记忆存储：与应用 Session 共用 Spring Data Redis 连接池。
     */
    @Bean
    public ChatMemoryStore chatMemoryStore(StringRedisTemplate redisTemplate,
                                           ChatMemoryProperties chatMemoryProperties) {
        return new SpringRedisChatMemoryStore(redisTemplate, chatMemoryProperties);
    }

    /**
     * 本地热点会话缓存：LRU 淘汰，未命中时回源 Redis 或 MySQL。
     */
    @Bean
    public ConversationMemoryCache conversationMemoryCache(ChatMemoryStore chatMemoryStore,
                                                           ChatHistoryService chatHistoryService,
                                                           ChatMemoryProperties chatMemoryProperties) {
        return new ConversationMemoryCache(chatMemoryStore, chatHistoryService, chatMemoryProperties);
    }
}
