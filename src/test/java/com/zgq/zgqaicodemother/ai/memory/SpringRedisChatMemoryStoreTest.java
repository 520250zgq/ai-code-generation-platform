package com.zgq.zgqaicodemother.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringRedisChatMemoryStoreTest {

    private static final long APP_ID = 42L;
    private static final Duration TTL = Duration.ofDays(1);
    private static final String KEY = "test:memory:42";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SpringRedisChatMemoryStore store;

    @BeforeEach
    void setUp() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setTtl(TTL);
        properties.setKeyPrefix("test:memory");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new SpringRedisChatMemoryStore(redisTemplate, properties);
    }

    @Test
    void writesMessagesWithNamespaceAndTtl() {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);

        store.updateMessages(APP_ID, List.of(UserMessage.from("你好"), AiMessage.from("收到")));

        verify(valueOperations).set(eq(KEY), json.capture(), eq(TTL));
        assertTrue(json.getValue().contains("你好"));
        assertTrue(json.getValue().contains("收到"));
    }

    @Test
    void readsMessagesWrittenByLangChainSerializer() {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        store.updateMessages(APP_ID, List.of(UserMessage.from("第一轮"), AiMessage.from("第一轮回复")));
        verify(valueOperations).set(eq(KEY), json.capture(), eq(TTL));
        when(valueOperations.get(KEY)).thenReturn(json.getValue());

        List<ChatMessage> messages = store.getMessages(APP_ID);

        assertEquals(2, messages.size());
        assertEquals("第一轮", assertInstanceOf(UserMessage.class, messages.get(0)).singleText());
        assertEquals("第一轮回复", assertInstanceOf(AiMessage.class, messages.get(1)).text());
    }

    @Test
    void missingOrCorruptedValueReturnsEmptyInsteadOfFailingChat() {
        when(valueOperations.get(KEY)).thenReturn(null);
        assertTrue(store.getMessages(APP_ID).isEmpty());

        when(valueOperations.get(KEY)).thenReturn("not-a-json-message-list");
        assertTrue(store.getMessages(APP_ID).isEmpty());
    }

    @Test
    void emptyWindowDeletesKeyAndDeleteRemovesKey() {
        store.updateMessages(APP_ID, List.of());
        verify(redisTemplate).delete(KEY);

        store.deleteMessages(APP_ID);
        verify(redisTemplate, times(2)).delete(KEY);
    }
}
