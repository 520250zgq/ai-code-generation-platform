package com.zgq.zgqaicodemother.ai.memory;

import lombok.Data;

import java.time.Duration;

/**
 * 对话记忆配置。
 *
 * <p>Redis 是对话记忆的持久化层，本地缓存只保存热点会话；两者使用同一份窗口大小和 TTL，
 * 避免本地缓存与 Redis 中的数据范围不一致。</p>
 */
@Data
public class ChatMemoryProperties {

    /**
     * 对话记忆在 Redis 中的过期时间。每次写入都会刷新 TTL。
     */
    private Duration ttl = Duration.ofDays(7);

    /**
     * 每个应用保留的最近消息条数（用户消息和 AI 消息合计）。
     *
     * <p>Vue 工具调用会产生大量“工具请求 + 工具结果”消息，窗口过小会让模型忘记已经生成的文件，
     * 因此默认值比普通对话更大，并可通过 {@code app.chat-memory.max-messages} 覆盖。</p>
     */
    private int maxMessages = 100;

    /**
     * 本地最多缓存的会话数。超出后按最近最少使用淘汰，未命中时回源 Redis/MySQL。
     */
    private int maxCachedConversations = 100;

    /**
     * Redis key 前缀，按应用维度追加 appId。
     */
    private String keyPrefix = "zgq:ai-code-mother:chat-memory:";
}
