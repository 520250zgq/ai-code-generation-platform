package com.zgq.zgqaicodemother.security.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * AI 接口固定窗口限流器。
 *
 * <p>正常情况下使用 Redis + Lua 原子计数，保证多实例共享同一份额度：先 {@code INCR}，
 * 第一次计数时设置过期时间，整个“计数 + 首次设置 TTL”在一个 Lua 脚本内完成，避免并发
 * 请求分别读到 0 后重复设置过期时间。窗口采用固定窗口而不是滑动窗口，是出于成本和
 * 可预期性的取舍：AI 接口只需要防止短时间刷量，固定窗口少维护一份请求时间列表，
 * 代价是窗口边界附近可能出现最多两倍瞬时流量。</p>
 *
 * <p>Redis 故障时的语义由 {@link RateLimitProperties#isFailOpen()} 明确控制：</p>
 * <ul>
 *     <li>{@code failOpen=true}：退回 JVM 本地内存继续限流，而不是直接放行。单机计数在
 *     多实例部署下不精确，但足以挡住持续刷接口的流量，并且异常日志按 30 秒节流，避免
 *     Redis 故障期间日志把磁盘打满。</li>
 *     <li>{@code failOpen=false}：直接放行，也就是“宁可暂时失去限流能力，也不阻断正常
 *     AI 对话业务”的 deny-open 语义。限流是保护能力，不应成为业务可用性的单点。</li>
 * </ul>
 *
 * <p>本地兜底使用 {@link ConcurrentHashMap} 保存固定窗口，并用 {@link ConcurrentLinkedQueue}
 * 近似记录插入顺序。Map 超过 {@code maxTrackedKeys} 时先清理过期窗口，再淘汰最旧的 key；
 * 并发下顺序和计数只保证近似，这是为了避免额外锁竞争而做的取舍。时间源通过构造器注入，
 * 单元测试可以推进虚拟时间，不需要真实 sleep。</p>
 *
 * <p>除 {@code failOpen=false} 的显式放行语义外，本类承诺限流组件自身异常不会导致业务
 * 请求失败；Redis 的同步调用由 Spring Data Redis 管理，不在这里创建线程或阻塞队列，
 * 也不捕获、吞掉 {@link InterruptedException}。</p>
 */
@Slf4j
@Component
public class RateLimiter {

    /**
     * Redis 脚本的默认结果类型为 Long，对应 Lua 返回的整数计数。
     */
    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private static final String DEFAULT_KEY_PREFIX = "zgq:ai-code-mother:rate-limit:";

    private static final int MAX_PREFIX_LENGTH = 128;

    private static final int MAX_BUSINESS_KEY_LENGTH = 256;

    private static final int MAX_REDIS_KEY_LENGTH = 512;

    private static final long WARN_INTERVAL_MILLIS = 30_000L;

    private static final long DEFAULT_LOCAL_CLEANUP_MILLIS = 60_000L;

    private static final long MIN_LOCAL_CLEANUP_MILLIS = 1_000L;

    private final StringRedisTemplate redisTemplate;

    private final RateLimitProperties properties;

    private final LongSupplier timeSupplier;

    private final ConcurrentHashMap<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<String> localKeyOrder = new ConcurrentLinkedQueue<>();

    /**
     * 队列和计数器的组合操作需要保持近似一致，用轻量锁而不是给窗口计数本身加锁；
     * 本地兜底只在 Redis 故障时使用，这个锁不会进入正常 Redis 路径。
     */
    private final Object localOrderLock = new Object();

    private final AtomicLong localKeyOrderCount = new AtomicLong();

    private final AtomicLong lastLocalCleanupMillis = new AtomicLong(0L);

    /**
     * 初始值向前偏移一个节流周期，使进程启动后的第一次故障可以立即记录告警。
     */
    private final AtomicLong lastWarningMillis = new AtomicLong(-WARN_INTERVAL_MILLIS);

    /**
     * Spring 装配使用的构造器，注入真实系统时钟。
     *
     * <p>类中还有一个接收 {@link LongSupplier} 的构造器供单元测试使用。多个构造器必须
     * 明确标注哪一个由 Spring 使用，否则容器无法判断应该注入哪一个。</p>
     *
     * @param redisTemplate Redis 字符串模板
     * @param properties    限流配置
     */
    @Autowired
    public RateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, System::currentTimeMillis);
    }

    /**
     * 可注入时间源的构造器。
     *
     * @param redisTemplate Redis 字符串模板，允许为 null；为 null 时按 Redis 不可用处理
     * @param properties    限流配置，允许为 null，此时使用默认配置
     * @param timeSupplier  毫秒时间源，允许为 null，此时使用系统时钟
     */
    public RateLimiter(StringRedisTemplate redisTemplate,
                       RateLimitProperties properties,
                       LongSupplier timeSupplier) {
        this.redisTemplate = redisTemplate;
        this.properties = properties == null ? new RateLimitProperties() : properties;
        this.timeSupplier = timeSupplier == null ? System::currentTimeMillis : timeSupplier;
    }

    /**
     * 尝试获取一次许可。
     *
     * @param key           完整业务 key，不包含配置的 Redis 前缀；允许为 null，此时归入统一的 unknown 维度
     * @param permits       当前窗口允许的请求数，小于 1 时按 1 处理
     * @param windowSeconds 窗口长度（秒），小于 1 时按 1 秒处理
     * @return 限流判断结果，内部异常时也不会返回 null
     */
    public RateLimitDecision acquire(String key, int permits, long windowSeconds) {
        int effectivePermits = Math.max(1, permits);
        long effectiveWindowSeconds = Math.max(1L, windowSeconds);
        try {
            if (!properties.isEnabled()) {
                return RateLimitDecision.allowed(effectivePermits);
            }
            return doAcquire(key, effectivePermits, effectiveWindowSeconds);
        } catch (RuntimeException e) {
            // 最后一道保险：即使本地兜底或配置读取出现未预期异常，也不能把异常抛给业务请求。
            warnThrottled("限流组件内部异常，本次请求直接放行以避免阻断业务", e);
            return RateLimitDecision.allowed(effectivePermits);
        }
    }

    /**
     * 优先走 Redis，失败后按配置选择本地兜底或显式放行。
     */
    private RateLimitDecision doAcquire(String key, int permits, long windowSeconds) {
        String redisKey = buildRedisKey(key);
        long windowMillis = toWindowMillis(windowSeconds);
        if (redisTemplate == null) {
            return fallbackToLocalOrAllow(redisKey, permits, windowSeconds, windowMillis,
                    new IllegalStateException("StringRedisTemplate 未配置"));
        }
        try {
            // 参数必须使用字符串，StringRedisTemplate 的脚本参数序列化器不接受 Long 对象。
            Long count = redisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(redisKey),
                    Long.toString(windowMillis));
            if (count == null) {
                // 连接或脚本返回异常时 execute 可能返回 null；继续保留限流能力，而不是全部放行。
                return fallbackToLocalOrAllow(redisKey, permits, windowSeconds, windowMillis,
                        new IllegalStateException("Redis 限流脚本未返回计数"));
            }
            long used = Math.max(0L, count);
            if (used <= permits) {
                return RateLimitDecision.allowed(permits - used);
            }
            long ttlMillis = readTtlMillis(redisKey, windowMillis);
            return RateLimitDecision.rejected(toRetryAfterSeconds(ttlMillis, windowSeconds));
        } catch (RuntimeException e) {
            return fallbackToLocalOrAllow(redisKey, permits, windowSeconds, windowMillis, e);
        }
    }

    /**
     * Redis 不可用时的统一降级入口。
     */
    private RateLimitDecision fallbackToLocalOrAllow(String redisKey,
                                                     int permits,
                                                     long windowSeconds,
                                                     long windowMillis,
                                                     RuntimeException cause) {
        boolean failOpen = properties.isFailOpen();
        if (failOpen) {
            warnThrottled("Redis 限流不可用，已降级为本地内存限流", cause);
            return acquireLocal(redisKey, permits, windowSeconds, windowMillis);
        }
        warnThrottled("Redis 限流不可用，failOpen=false，本次直接放行以避免阻断业务", cause);
        return RateLimitDecision.allowed(permits);
    }

    /**
     * 读取 Redis 中当前窗口的剩余毫秒数。
     *
     * <p>此时 Redis 已经明确拒绝了本次请求，即使读取 TTL 失败也可以安全地按完整窗口拒绝，
     * 不能因为“读不到 TTL”而改走本地计数或放行，否则会放大流量。TTL 的异常只记录 debug，
     * 避免每次被限流都刷一条 warn。</p>
     */
    private long readTtlMillis(String redisKey, long windowMillis) {
        try {
            Long ttlMillis = redisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);
            return ttlMillis == null ? windowMillis : ttlMillis;
        } catch (RuntimeException e) {
            log.debug("读取限流窗口剩余时间失败，按完整窗口计算", e);
            return windowMillis;
        }
    }

    /**
     * 构造带全局前缀的 Redis key，并把总长度控制在安全范围。
     *
     * <p>前缀和业务 key 都来自配置/代码而不是用户输入，正常情况下远低于上限；截断是防止
     * 误配置或异常调用把超长 key 写入 Redis。切面还会提前截断规则名、用户 id 和 IP 片段，
     * 因此这里的截断只是最后一道边界保护。</p>
     */
    private String buildRedisKey(String key) {
        String prefix = properties.getKeyPrefix();
        if (prefix == null) {
            prefix = DEFAULT_KEY_PREFIX;
        }
        if (prefix.length() > MAX_PREFIX_LENGTH) {
            prefix = prefix.substring(0, MAX_PREFIX_LENGTH);
        }
        String businessKey = key == null || key.isBlank() ? "unknown" : key.trim();
        if (businessKey.length() > MAX_BUSINESS_KEY_LENGTH) {
            businessKey = businessKey.substring(0, MAX_BUSINESS_KEY_LENGTH);
        }
        String redisKey = prefix + businessKey;
        return redisKey.length() > MAX_REDIS_KEY_LENGTH
                ? redisKey.substring(0, MAX_REDIS_KEY_LENGTH)
                : redisKey;
    }

    /**
     * 本地固定窗口计数。
     */
    private RateLimitDecision acquireLocal(String key,
                                          int permits,
                                          long windowSeconds,
                                          long windowMillis) {
        long now = now();
        maybeCleanupLocalWindows(now);
        LocalWindow window = getOrCreateLocalWindow(key, now, windowMillis);
        ensureLocalCapacity(now);
        long count;
        long remainingMillis;
        // 只锁单个窗口：不同用户/IP 的计数互不阻塞，而同一窗口的“重置 + 自增”必须原子完成。
        synchronized (window) {
            if (now < window.windowStartMillis || now >= window.windowEndMillis) {
                window.windowStartMillis = now;
                window.windowEndMillis = addSaturated(now, windowMillis);
                window.count.set(0L);
            }
            count = window.count.incrementAndGet();
            remainingMillis = window.windowEndMillis - now;
        }
        if (count <= permits) {
            return RateLimitDecision.allowed(permits - count);
        }
        return RateLimitDecision.rejected(
                toRetryAfterSeconds(Math.max(1L, remainingMillis), windowSeconds));
    }

    /**
     * 取得或创建窗口。并发创建时用 putIfAbsent 保证只保留一个对象，避免重复计数。
     */
    private LocalWindow getOrCreateLocalWindow(String key, long now, long windowMillis) {
        LocalWindow existing = localWindows.get(key);
        if (existing != null) {
            return existing;
        }
        LocalWindow candidate = new LocalWindow(now, windowMillis);
        LocalWindow raced = localWindows.putIfAbsent(key, candidate);
        if (raced != null) {
            return raced;
        }
        enqueueLocalKey(key);
        return candidate;
    }

    /**
     * 按配置间隔顺带清理过期窗口，不创建后台线程，也不依赖请求结束回调。
     */
    private void maybeCleanupLocalWindows(long now) {
        long lastCleanup = lastLocalCleanupMillis.get();
        if (lastCleanup != 0L && now >= lastCleanup
                && now - lastCleanup < localWindowCleanupIntervalMillis()) {
            return;
        }
        if (!lastLocalCleanupMillis.compareAndSet(lastCleanup, now)) {
            return;
        }
        removeExpiredLocalWindows(now);
    }

    private void removeExpiredLocalWindows(long now) {
        for (Map.Entry<String, LocalWindow> entry : localWindows.entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                localWindows.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 保证本地 Map 有上限：先清过期窗口，再按近似插入顺序淘汰，最后用 keySet 遍历兜底。
     *
     * <p>队列在并发下可能包含已淘汰 key 的残留项，也可能因压缩与并发入队交错而缺失少量
     * 新 key。因此淘汰顺序只保证近似，但 Map 的上限必须优先保证，避免 Redis 故障时内存
     * 无界增长。</p>
     */
    private void ensureLocalCapacity(long now) {
        int maxTrackedKeys = maxTrackedKeys();
        if (localWindows.size() <= maxTrackedKeys) {
            return;
        }
        removeExpiredLocalWindows(now);
        while (localWindows.size() > maxTrackedKeys) {
            String oldestKey = pollLocalKey();
            if (oldestKey == null) {
                break;
            }
            LocalWindow window = localWindows.get(oldestKey);
            if (window != null) {
                localWindows.remove(oldestKey, window);
            }
        }
        if (localWindows.size() > maxTrackedKeys) {
            for (String key : localWindows.keySet()) {
                if (localWindows.size() <= maxTrackedKeys) {
                    break;
                }
                localWindows.remove(key);
            }
        }
    }

    private void enqueueLocalKey(String key) {
        synchronized (localOrderLock) {
            localKeyOrder.offer(key);
            long queued = localKeyOrderCount.incrementAndGet();
            // 队列里的 key 会因窗口过期、容量淘汰而变成残留项；超过两倍上限时按当前 Map
            // 重建一次，避免长时间运行后队列本身成为新的内存泄漏点。
            if (queued > (long) maxTrackedKeys() * 2L) {
                rebuildLocalKeyOrder();
            }
        }
    }

    private String pollLocalKey() {
        synchronized (localOrderLock) {
            String key = localKeyOrder.poll();
            if (key != null) {
                localKeyOrderCount.decrementAndGet();
            }
            return key;
        }
    }

    /**
     * 调用方必须持有 {@link #localOrderLock}。
     */
    private void rebuildLocalKeyOrder() {
        localKeyOrder.clear();
        localKeyOrderCount.set(0L);
        for (String key : localWindows.keySet()) {
            localKeyOrder.offer(key);
            localKeyOrderCount.incrementAndGet();
        }
    }

    private boolean isExpired(LocalWindow window, long now) {
        return now < window.windowStartMillis || now >= window.windowEndMillis;
    }

    private int maxTrackedKeys() {
        return Math.max(1, properties.getMaxTrackedKeys());
    }

    private long localWindowCleanupIntervalMillis() {
        Duration interval = properties.getLocalWindowCleanupInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return DEFAULT_LOCAL_CLEANUP_MILLIS;
        }
        try {
            // 间隔过小会让每个请求都遍历 Map，设置一个下限避免配置失误放大 CPU 开销。
            return Math.max(MIN_LOCAL_CLEANUP_MILLIS, interval.toMillis());
        } catch (ArithmeticException e) {
            // Duration 大到无法转换成毫秒时，等价于不主动清理；容量保护仍然生效。
            return Long.MAX_VALUE;
        }
    }

    private long now() {
        return timeSupplier.getAsLong();
    }

    /**
     * 按 30 秒节流记录限流降级或内部告警。
     *
     * <p>Redis 故障时每个请求都会进入降级路径，如果逐条打印会迅速淹没日志。这里用 CAS
     * 保证多线程下最多只有一个线程更新告警时间，其余线程直接返回。</p>
     */
    private void warnThrottled(String message, RuntimeException error) {
        try {
            long now = now();
            long lastWarning = lastWarningMillis.get();
            long elapsed = now - lastWarning;
            if (elapsed >= 0L && elapsed < WARN_INTERVAL_MILLIS) {
                return;
            }
            if (!lastWarningMillis.compareAndSet(lastWarning, now)) {
                return;
            }
            String reason = error == null ? "未知原因" : error.getMessage();
            log.warn("{}，原因={}", message, reason);
            if (error != null) {
                log.debug("限流降级异常详情", error);
            }
        } catch (RuntimeException ignored) {
            // 告警路径自身失败时放弃日志，不能反过来影响业务请求。
        }
    }

    private static long toRetryAfterSeconds(long ttlMillis, long windowSeconds) {
        if (ttlMillis <= 0L) {
            return 1L;
        }
        long seconds = ttlMillis / 1000L;
        if (ttlMillis % 1000L != 0L) {
            seconds++;
        }
        return Math.max(1L, Math.min(windowSeconds, seconds));
    }

    private static long toWindowMillis(long windowSeconds) {
        if (windowSeconds > Long.MAX_VALUE / 1000L) {
            return Long.MAX_VALUE;
        }
        return windowSeconds * 1000L;
    }

    private static long addSaturated(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    /**
     * 本地固定窗口。
     *
     * <p>{@code windowStartMillis} 和 {@code windowEndMillis} 由窗口对象锁保护，同时标记
     * volatile 让清理线程可以无锁判断过期；计数使用 AtomicLong，便于调试和后续统计扩展。</p>
     */
    private static final class LocalWindow {

        private volatile long windowStartMillis;

        private volatile long windowEndMillis;

        private final AtomicLong count = new AtomicLong();

        private LocalWindow(long windowStartMillis, long windowMillis) {
            this.windowStartMillis = windowStartMillis;
            this.windowEndMillis = addSaturated(windowStartMillis, windowMillis);
        }
    }
}
