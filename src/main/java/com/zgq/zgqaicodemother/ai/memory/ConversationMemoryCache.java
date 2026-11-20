package com.zgq.zgqaicodemother.ai.memory;

import com.zgq.zgqaicodemother.ai.concurrency.KeyedLock;
import com.zgq.zgqaicodemother.constant.ChatHistoryConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 对话记忆的本地热点缓存。
 *
 * <p>每个应用对应一个 {@link ChatMemory}。第一次访问时优先从 Redis 恢复；Redis 未命中
 * （首次对话、过期或实例刚重启）时，从 MySQL 的 chat_history 回源最近若干条消息，
 * 让 AI 在服务重启后仍能理解上下文。</p>
 *
 * <p>本地缓存按最近最少使用淘汰，且淘汰不会删除 Redis 数据；下次访问会重新从 Redis 恢复。
 * 应用被删除时调用 {@link #evict(Long)} 同时清理本地缓存和 Redis key。</p>
 *
 * <p><b>并发设计：</b>初始化一份记忆需要一次 Redis 读取，未命中时还要查一次 MySQL，属于
 * 毫秒级的阻塞 IO。早期实现把整个初始化过程放在同一把全局锁里，导致任何时刻只有一个
 * 请求能进入 AI 生成，其他请求都在等这把锁。现在把锁拆成两层：</p>
 * <ul>
 *   <li>LRU 映射表只在读写映射的瞬间加锁，锁内不做任何 IO；</li>
 *   <li>同一应用使用独立的 {@link KeyedLock}，保证并发首访只回源一次，
 *       而不同应用之间可以完全并行地加载各自的记忆。</li>
 * </ul>
 */
@Slf4j
public class ConversationMemoryCache {

    /**
     * 等待其他线程完成同一应用记忆加载的默认上限。加载只涉及一次 Redis 和一次数据库查询，
     * 超过该时间通常说明下游已经故障，继续等待只会拖垮请求。
     */
    static final Duration DEFAULT_LOAD_TIMEOUT = Duration.ofSeconds(10);

    private final ChatMemoryStore chatMemoryStore;

    private final ChatHistoryService chatHistoryService;

    private final int maxMessages;

    private final int maxCachedConversations;

    private final Duration loadTimeout;

    /**
     * accessOrder=true 的 LinkedHashMap 实现 LRU。所有访问都在同一把锁内，但锁内只做
     * get/put/remove，不执行 Redis 或数据库操作。
     */
    private final Map<Long, ChatMemory> memories;

    /**
     * 按应用粒度的加载锁：同一应用并发首访只回源一次，不同应用互不阻塞。
     */
    private final KeyedLock<Long> loadLocks = new KeyedLock<>();

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
        this.loadTimeout = properties.getLoadTimeout() == null || properties.getLoadTimeout().isNegative()
                ? DEFAULT_LOAD_TIMEOUT : properties.getLoadTimeout();
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
     *
     * @param appId 应用 id
     * @return 该应用独享的记忆窗口
     */
    public ChatMemory get(Long appId) {
        Assert.notNull(appId, "appId 不能为空");
        ChatMemory cached = lookup(appId);
        if (cached != null) {
            return cached;
        }
        acquireLoadLock(appId);
        try {
            // 双重检查：等在锁上的线程可能已经由前一个线程完成加载。
            ChatMemory loaded = lookup(appId);
            if (loaded != null) {
                return loaded;
            }
            ChatMemory created = createMemory(appId);
            store(appId, created);
            return created;
        } finally {
            loadLocks.unlock(appId);
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
     * 读取本地热点缓存；只持有短暂的对象锁，不做任何 IO。
     */
    private ChatMemory lookup(Long appId) {
        synchronized (memories) {
            return memories.get(appId);
        }
    }

    /**
     * 写入本地热点缓存；LRU 淘汰在 put 内部完成，不会删除 Redis 数据。
     */
    private void store(Long appId, ChatMemory memory) {
        synchronized (memories) {
            memories.put(appId, memory);
        }
    }

    /**
     * 获取应用级加载锁，超时后返回可读错误。
     *
     * <p>分布式部署下每个实例都会独立回源一次，这是可以接受的：回源是幂等的读取，
     * 真正需要避免的是同一实例内多个请求同时初始化同一份记忆。</p>
     */
    private void acquireLoadLock(Long appId) {
        long nanos = loadTimeout.toNanos();
        try {
            if (!loadLocks.tryLock(appId, nanos, TimeUnit.NANOSECONDS)) {
                throw new BusinessException(ErrorCode.SERVICE_BUSY, "对话上下文加载超时，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_BUSY, "对话上下文加载被中断，请稍后重试");
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
