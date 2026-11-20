package com.zgq.zgqaicodemother.ai.concurrency;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按业务 key 加锁的工具，保证同一个 key 互斥、不同 key 并行。
 *
 * <p>为什么不直接用固定数量的分段锁：AI 生成的一轮可能持续数分钟，分段锁一旦哈希冲突，
 * 两个毫不相关的应用会被串行化几分钟，这类偶发“卡顿”极难排查。这里为每个 key 维护独立锁，
 * 并通过引用计数在无人使用时删除条目，既保证互斥又不会随应用数量无限增长。</p>
 *
 * <p>使用约束：{@link #tryLock} 成功返回 true 时，调用方必须在 finally 中调用
 * {@link #unlock(Object)}；tryLock 返回 false 或抛异常时内部已经释放引用计数，调用方不能再 unlock。</p>
 *
 * @param <K> 业务 key 类型，必须正确实现 equals/hashCode（Long、String 等都可以）
 */
public final class KeyedLock<K> {

    /**
     * key 到锁条目的映射；条目在引用计数归零时移除。
     */
    private final ConcurrentHashMap<K, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 尝试获取指定 key 的锁。
     *
     * @param key     业务 key
     * @param timeout 最长等待时间，0 表示只尝试一次
     * @param unit    时间单位
     * @return 获取成功返回 true；超时返回 false
     * @throws InterruptedException 等待期间线程被中断，中断标记不清除，交由调用方处理
     */
    public boolean tryLock(K key, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(key, "锁 key 不能为空");
        Objects.requireNonNull(unit, "时间单位不能为空");
        Entry entry = retain(key);
        boolean acquired;
        try {
            acquired = entry.lock.tryLock(Math.max(0L, timeout), unit);
        } catch (InterruptedException e) {
            // 中断时不会持有锁，只需要归还引用计数，再把中断交给上层决定是否继续。
            releaseReference(key, entry);
            throw e;
        }
        if (!acquired) {
            releaseReference(key, entry);
        }
        return acquired;
    }

    /**
     * 释放指定 key 的锁。
     *
     * <p>只有当前线程持有该 key 的锁时才能调用，否则抛出 {@link IllegalMonitorStateException}，
     * 让“多释放/错释放”在开发阶段立刻暴露，而不是悄悄破坏互斥语义。</p>
     *
     * @param key 业务 key
     */
    public void unlock(K key) {
        Objects.requireNonNull(key, "锁 key 不能为空");
        Entry entry = entries.get(key);
        if (entry == null || !entry.lock.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("当前线程未持有该 key 的锁：" + key);
        }
        // 必须先释放锁再归还引用：否则可能在“归还引用完成”与“真正 unlock”之间被其他线程
        // 认为条目已废弃并新建一把锁，导致同一 key 出现两把锁。
        entry.lock.unlock();
        releaseReference(key, entry);
    }

    /**
     * 当前仍在跟踪的 key 数量，供监控与测试使用；正常业务稳定后应远小于应用总数。
     *
     * @return 条目数量
     */
    public int trackedKeys() {
        return entries.size();
    }

    /**
     * 增加一次引用计数并返回条目；compute 对同一个 key 是原子的，不会出现重复创建。
     */
    private Entry retain(K key) {
        return entries.compute(key, (ignored, existing) -> {
            Entry entry = existing == null ? new Entry() : existing;
            entry.references.incrementAndGet();
            return entry;
        });
    }

    /**
     * 归还引用计数；归零时把条目从映射中移除，避免锁对象随应用数量累积。
     *
     * <p>递减与移除必须在 computeIfPresent 内完成：如果在递减之后再单独 remove，
     * 中间可能有新线程递增了引用计数，导致它持有的条目被误删、进而为同一个 key 新建第二把锁。</p>
     */
    private void releaseReference(K key, Entry entry) {
        entries.computeIfPresent(key, (ignored, current) -> {
            if (current != entry) {
                return current;
            }
            return current.references.decrementAndGet() > 0 ? current : null;
        });
    }

    /**
     * 单个 key 的锁与引用计数。
     */
    private static final class Entry {

        /**
         * 公平锁：等待时间接近时按先来后到获取，避免同一应用被连续请求饿死。
         */
        private final ReentrantLock lock = new ReentrantLock(true);

        /**
         * 持有或等待该锁的线程数；归零表示可以从映射中移除。
         */
        private final AtomicInteger references = new AtomicInteger();
    }
}
