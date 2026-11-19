package com.zgq.zgqaicodemother.ai.memory;

import cn.hutool.core.util.StrUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring Data Redis 的对话记忆存储。
 *
 * <p>复用应用统一的 Redis 连接池，而不是再创建一个 Jedis 客户端。消息使用 LangChain4j
 * 自带的 JSON 编解码器保存，一个应用（appId）对应一个 Redis key，写入时刷新 TTL。</p>
 */
@Slf4j
public class SpringRedisChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redisTemplate;

    private final Duration ttl;

    private final String keyPrefix;

    /**
     * @param redisTemplate Spring Data Redis 模板，与应用 Session 共用连接工厂
     * @param properties    记忆 TTL 与 key 前缀
     */
    public SpringRedisChatMemoryStore(StringRedisTemplate redisTemplate, ChatMemoryProperties properties) {
        Assert.notNull(redisTemplate, "redisTemplate 不能为空");
        Assert.notNull(properties, "对话记忆配置不能为空");
        Assert.notNull(properties.getTtl(), "对话记忆 TTL 不能为空");
        Assert.isTrue(!properties.getTtl().isNegative() && !properties.getTtl().isZero(),
                "对话记忆 TTL 必须大于 0");
        Assert.isTrue(StrUtil.isNotBlank(properties.getKeyPrefix()), "对话记忆 key 前缀不能为空");
        this.redisTemplate = redisTemplate;
        this.ttl = properties.getTtl();
        this.keyPrefix = properties.getKeyPrefix().endsWith(":")
                ? properties.getKeyPrefix() : properties.getKeyPrefix() + ":";
    }

    /**
     * 读取一个会话的全部消息；Redis 中没有记录时返回空列表。
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = toRedisKey(memoryId);
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            return messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        } catch (RuntimeException e) {
            // 历史版本或人工写入导致的脏数据不应阻断整轮对话；丢弃后由数据库历史重新回源。
            log.warn("对话记忆反序列化失败，已忽略 Redis 记录，key={}", key, e);
            return new ArrayList<>();
        }
    }

    /**
     * 覆盖写入一个会话的消息窗口，并刷新 TTL；空列表等价于删除。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            deleteMessages(memoryId);
            return;
        }
        String key = toRedisKey(memoryId);
        redisTemplate.opsForValue().set(key, ChatMessageSerializer.messagesToJson(messages), ttl);
    }

    /**
     * 删除一个会话的持久化记忆，用于应用删除或手动清理。
     */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(toRedisKey(memoryId));
    }

    private String toRedisKey(Object memoryId) {
        Assert.notNull(memoryId, "对话记忆 id 不能为空");
        return keyPrefix + memoryId;
    }
}
