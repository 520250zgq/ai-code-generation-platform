package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.ai.memory.InMemoryChatMemoryStore;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * AI 集成测试的轻量记忆依赖：不连接 Redis/MySQL，只验证 AiServices 装配与真实模型调用。
 */
@TestConfiguration
public class TestAiMemoryConfig {

    @Bean
    @Primary
    public ChatMemoryStore inMemoryChatMemoryStore() {
        return new InMemoryChatMemoryStore();
    }

    @Bean
    public ChatHistoryService chatHistoryService() {
        ChatHistoryService service = Mockito.mock(ChatHistoryService.class);
        when(service.listRecentMessages(anyLong(), anyInt())).thenReturn(List.of());
        return service;
    }

    /**
     * 生产环境的 Redis 记忆存储仍会被注册，提供 mock 模板避免测试上下文启动时连接 Redis。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate() {
        return Mockito.mock(StringRedisTemplate.class);
    }
}
