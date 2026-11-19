package com.zgq.zgqaicodemother.ai.memory;

import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryCacheTest {

    private static final long APP_ID = 88L;

    @Mock
    private ChatHistoryService chatHistoryService;

    private InMemoryChatMemoryStore chatMemoryStore;

    private ConversationMemoryCache cache;

    @BeforeEach
    void setUp() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setMaxMessages(6);
        properties.setMaxCachedConversations(2);
        chatMemoryStore = new InMemoryChatMemoryStore();
        cache = new ConversationMemoryCache(chatMemoryStore, chatHistoryService, properties);
    }

    @Test
    void restoresChronologicalHistoryAndSkipsFailurePlaceholder() {
        when(chatHistoryService.listRecentMessages(APP_ID, 6)).thenReturn(List.of(
                history(1L, ChatHistoryMessageTypeEnum.USER, "第一轮需求"),
                history(2L, ChatHistoryMessageTypeEnum.AI, "第一轮代码"),
                history(3L, ChatHistoryMessageTypeEnum.AI, "AI 回复失败：timeout"),
                history(4L, ChatHistoryMessageTypeEnum.USER, "第二轮需求")
        ));

        List<ChatMessage> messages = cache.get(APP_ID).messages();

        assertEquals(3, messages.size());
        assertEquals("第一轮需求", assertInstanceOf(UserMessage.class, messages.get(0)).singleText());
        assertEquals("第一轮代码", assertInstanceOf(AiMessage.class, messages.get(1)).text());
        assertEquals("第二轮需求", assertInstanceOf(UserMessage.class, messages.get(2)).singleText());
        verify(chatHistoryService).listRecentMessages(APP_ID, 6);
    }

    @Test
    void prefersRedisOverDatabaseOnFirstAccess() {
        chatMemoryStore.updateMessages(APP_ID,
                List.of(UserMessage.from("Redis 中的用户消息"), AiMessage.from("Redis 中的回复")));

        List<ChatMessage> messages = cache.get(APP_ID).messages();

        assertEquals(2, messages.size());
        assertEquals("Redis 中的用户消息", assertInstanceOf(UserMessage.class, messages.get(0)).singleText());
        verifyNoInteractions(chatHistoryService);
    }

    @Test
    void cachesMemoryAndEvictsLeastRecentlyUsedConversation() {
        ChatMemory first = cache.get(1L);
        ChatMemory second = cache.get(2L);
        assertSame(first, cache.get(1L));
        assertEquals(2, cache.cachedCount());

        cache.get(3L);

        assertEquals(2, cache.cachedCount());
        assertSame(first, cache.get(1L));
        assertNotSame(second, cache.get(2L));
    }

    @Test
    void evictRemovesLocalCacheAndRedisMemory() {
        cache.get(APP_ID);
        chatMemoryStore.updateMessages(APP_ID, List.of(UserMessage.from("待删除")));

        cache.evict(APP_ID);

        assertEquals(0, cache.cachedCount());
        assertTrue(chatMemoryStore.getMessages(APP_ID).isEmpty());
    }

    @Test
    void repeatedAccessDoesNotReloadDatabase() {
        when(chatHistoryService.listRecentMessages(APP_ID, 6)).thenReturn(List.of(
                history(1L, ChatHistoryMessageTypeEnum.USER, "同一条历史")
        ));

        cache.get(APP_ID).messages();
        cache.get(APP_ID).messages();

        verify(chatHistoryService, times(1)).listRecentMessages(APP_ID, 6);
    }

    private static ChatHistory history(long id, ChatHistoryMessageTypeEnum type, String message) {
        return ChatHistory.builder()
                .id(id)
                .appId(APP_ID)
                .messageType(type.getValue())
                .message(message)
                .build();
    }
}
