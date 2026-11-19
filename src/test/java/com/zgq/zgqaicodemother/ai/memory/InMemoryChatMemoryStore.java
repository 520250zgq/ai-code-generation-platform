package com.zgq.zgqaicodemother.ai.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存版 ChatMemoryStore，行为与 Redis 存储一致但不依赖外部服务。
 */
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> messages = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return new ArrayList<>(messages.getOrDefault(memoryId, List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        this.messages.put(memoryId, new ArrayList<>(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        messages.remove(memoryId);
    }
}
