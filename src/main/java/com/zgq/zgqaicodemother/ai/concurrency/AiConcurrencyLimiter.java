package com.zgq.zgqaicodemother.ai.concurrency;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 生成并发控制器。
 *
 * <p>解决两个层面的问题：</p>
 * <ul>
 *   <li>全局并发：模型调用是长耗时网络 IO，必须有明确上限，超限时快速返回可读提示，
 *       而不是把线程和后端连接全部占满。</li>
 *   <li>同一应用互斥：一个应用共享一份对话记忆（{@code ChatMemory}）和同一个工程目录，
 *       两轮并发生成会互相覆盖文件、把两轮工具调用消息交叉写进同一份上下文，最终生成
 *       一个自相矛盾的工程。因此同一应用同时只允许一轮生成。</li>
 * </ul>
 *
 * <p>两个维度使用不同的等待策略：全局许可允许排队（用户能接受“稍等一会儿”），
 * 同一应用则快速失败（用户连续点击两次时应当立刻被告知正在生成中）。</p>
 *
 * <p>许可通过 {@link Lease} 归还：调用方必须在流结束、取消或异常时关闭租约，
 * 否则许可会泄漏并逐步把服务锁死。</p>
 */
@Slf4j
public class AiConcurrencyLimiter {

    private final AiConcurrencyProperties properties;

    /**
     * 全局并发许可。公平模式保证先到先得，避免低峰期请求被持续抢占。
     */
    private final Semaphore globalPermits;

    /**
     * 应用级互斥锁，按 appId 隔离。
     */
    private final KeyedLock<Long> appLocks = new KeyedLock<>();

    /**
     * 全局许可总数，用于统计活跃请求数。
     */
    private final int maxConcurrentRequests;

    public AiConcurrencyLimiter(AiConcurrencyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "AI 并发配置不能为空");
        // 误配置为 0 或负数时退化为 1：宁可串行，也不能让服务在启动后拒绝所有请求。
        this.maxConcurrentRequests = Math.max(1, properties.getMaxConcurrentRequests());
        this.globalPermits = new Semaphore(maxConcurrentRequests, true);
    }

    /**
     * 获取一轮 AI 生成的许可：先确保同一应用没有并发生成，再申请全局并发许可。
     *
     * <p>加锁顺序固定为“应用锁 → 全局许可”。反过来会让请求在等待应用锁时白占全局许可，
     * 高峰期会把并发额度耗在排队上。</p>
     *
     * @param appId 应用 id
     * @return 需要在生成结束时关闭的租约
     * @throws BusinessException 同一应用正在生成，或全局并发已满且等待超时
     */
    public Lease acquire(Long appId) {
        Objects.requireNonNull(appId, "应用 id 不能为空");
        return acquire(appId, true);
    }

    /**
     * 只申请应用级互斥，不占用全局许可。
     *
     * <p>工作流等已经有独立并发控制的链路使用该方法，避免叠加两层全局上限互相顶掉额度。</p>
     *
     * @param appId 应用 id
     * @return 需要在执行结束时关闭的租约
     * @throws BusinessException 同一应用正在生成
     */
    public Lease acquireApp(Long appId) {
        Objects.requireNonNull(appId, "应用 id 不能为空");
        return acquire(appId, false);
    }

    /**
     * 当前正在执行的 AI 生成轮数，供监控接口和测试使用。
     *
     * @return 活跃轮数
     */
    public int activeRequests() {
        return maxConcurrentRequests - globalPermits.availablePermits();
    }

    /**
     * 全局并发上限。
     *
     * @return 许可总数
     */
    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    /**
     * 是否启用了并发限制。
     *
     * @return 启用返回 true
     */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /**
     * 申请应用锁，并按需申请全局许可。
     */
    private Lease acquire(Long appId, boolean withGlobalPermit) {
        if (!properties.isEnabled()) {
            // 关闭并发控制时仍然保留同一应用互斥：两轮并发生成会破坏文件与对话记忆。
            return acquireAppLockOnly(appId);
        }
        boolean appLocked = false;
        boolean globalAcquired = false;
        try {
            appLocked = tryLockApp(appId);
            if (!appLocked) {
                throw new BusinessException(ErrorCode.SERVICE_BUSY, "该应用正在生成中，请等待当前生成结束");
            }
            if (withGlobalPermit) {
                globalAcquired = tryAcquireGlobal();
                if (!globalAcquired) {
                    throw new BusinessException(ErrorCode.SERVICE_BUSY, "AI 服务繁忙，请稍后重试");
                }
            }
            return new Lease(appId, appLocked, globalAcquired);
        } catch (RuntimeException e) {
            // 任何一步失败都要把已经拿到的资源还回去，否则一次失败就会永久占用许可。
            releaseQuietly(appId, appLocked, globalAcquired);
            throw e;
        }
    }

    /**
     * 关闭并发控制时的最小互斥：只保证同一应用不并发生成。
     */
    private Lease acquireAppLockOnly(Long appId) {
        if (!tryLockApp(appId)) {
            throw new BusinessException(ErrorCode.SERVICE_BUSY, "该应用正在生成中，请等待当前生成结束");
        }
        return new Lease(appId, true, false);
    }

    /**
     * 尝试获取应用级锁；等待时间取 perAppAcquireTimeout。
     */
    private boolean tryLockApp(Long appId) {
        Duration timeout = properties.getPerAppAcquireTimeout();
        long nanos = timeout == null ? 0L : Math.max(0L, timeout.toNanos());
        try {
            // ReentrantLock 本身支持带超时的等待，一次等待即可，不需要拆成多个时间片轮询。
            return appLocks.tryLock(appId, nanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_BUSY, "生成排队被中断，请稍后重试");
        }
    }

    /**
     * 尝试在超时时间内取得全局许可。
     */
    private boolean tryAcquireGlobal() {
        Duration timeout = properties.getAcquireTimeout();
        long millis = timeout == null ? 0L : Math.max(0L, timeout.toMillis());
        try {
            return globalPermits.tryAcquire(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SERVICE_BUSY, "生成排队被中断，请稍后重试");
        }
    }

    /**
     * 释放已经获取的资源；释放过程本身不允许抛异常覆盖原始业务错误。
     */
    private void releaseQuietly(Long appId, boolean appLocked, boolean globalAcquired) {
        if (globalAcquired) {
            globalPermits.release();
        }
        if (appLocked) {
            try {
                appLocks.unlock(appId);
            } catch (RuntimeException e) {
                log.warn("释放 AI 生成应用锁失败，appId={}，原因={}", appId, e.getMessage());
            }
        }
    }

    /**
     * 一轮生成的许可租约。
     *
     * <p>{@link #close()} 幂等：Reactor 的 doFinally 在部分异常路径上可能被调用多次，
     * 重复 release 会让信号量计数虚高、并发上限失效。</p>
     */
    public final class Lease implements AutoCloseable {

        private final Long appId;

        private final boolean appLocked;

        private final boolean globalAcquired;

        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Long appId, boolean appLocked, boolean globalAcquired) {
            this.appId = appId;
            this.appLocked = appLocked;
            this.globalAcquired = globalAcquired;
        }

        /**
         * 归还本轮占用的全部资源。
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            releaseQuietly(appId, appLocked, globalAcquired);
        }

        /**
         * 本轮对应的应用 id，便于日志和测试断言。
         *
         * @return 应用 id
         */
        public Long appId() {
            return appId;
        }
    }
}
