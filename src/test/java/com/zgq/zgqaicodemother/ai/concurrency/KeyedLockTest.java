package com.zgq.zgqaicodemother.ai.concurrency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按业务 key 加锁的工具测试：同一 key 互斥、不同 key 并行、引用计数归零后清理。
 */
class KeyedLockTest {

    @Test
    void sameKeyIsMutuallyExclusive() throws Exception {
        KeyedLock<Long> keyedLock = new KeyedLock<>();
        assertTrue(keyedLock.tryLock(1L, 0, TimeUnit.MILLISECONDS));

        CountDownLatch attempted = new CountDownLatch(1);
        boolean[] acquiredWhileHeld = new boolean[1];
        Thread contender = Thread.ofVirtual().start(() -> {
            try {
                acquiredWhileHeld[0] = keyedLock.tryLock(1L, 10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                attempted.countDown();
            }
        });

        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        contender.join(2000);
        assertFalse(acquiredWhileHeld[0], "同一 key 在持有期间不能被第二个线程获取");

        keyedLock.unlock(1L);
        assertTrue(keyedLock.tryLock(1L, 0, TimeUnit.MILLISECONDS), "释放后应可重新获取");
        keyedLock.unlock(1L);
    }

    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        KeyedLock<Long> keyedLock = new KeyedLock<>();
        assertTrue(keyedLock.tryLock(1L, 0, TimeUnit.MILLISECONDS));

        assertTrue(keyedLock.tryLock(2L, 0, TimeUnit.MILLISECONDS),
                "不同 key 不应互相阻塞，否则一个大模型任务会拖住所有应用");
        keyedLock.unlock(2L);

        // 同一线程对同一 key 可重入，但必须按次数释放。
        assertTrue(keyedLock.tryLock(1L, 0, TimeUnit.MILLISECONDS));
        keyedLock.unlock(1L);
        keyedLock.unlock(1L);
    }

    @Test
    void entriesAreRemovedAfterLastRelease() throws Exception {
        KeyedLock<Long> keyedLock = new KeyedLock<>();
        assertTrue(keyedLock.tryLock(1L, 0, TimeUnit.MILLISECONDS));
        keyedLock.unlock(1L);

        assertEquals(0, keyedLock.trackedKeys(), "引用计数归零后必须清理条目，避免随应用数量无限增长");
    }

    @Test
    void failedTryLockDoesNotLeakReference() throws Exception {
        KeyedLock<Long> keyedLock = new KeyedLock<>();
        assertTrue(keyedLock.tryLock(5L, 0, TimeUnit.MILLISECONDS));

        // 必须在另一个线程里尝试：ReentrantLock 对同一线程是可重入的，同线程会很自然地成功。
        CountDownLatch result = new CountDownLatch(1);
        boolean[] acquired = new boolean[1];
        Thread contender = Thread.ofVirtual().start(() -> {
            try {
                acquired[0] = keyedLock.tryLock(5L, 1, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                result.countDown();
            }
        });
        assertTrue(result.await(2, TimeUnit.SECONDS));
        contender.join(2000);
        assertFalse(acquired[0]);

        keyedLock.unlock(5L);
        assertEquals(0, keyedLock.trackedKeys(), "获取失败的那次尝试不能泄漏引用计数");
    }

    @Test
    void unlockWithoutHoldingLockFailsFast() {
        KeyedLock<Long> keyedLock = new KeyedLock<>();
        assertThrows(IllegalMonitorStateException.class, () -> keyedLock.unlock(3L),
                "未持锁时释放必须立刻暴露，而不是悄悄破坏互斥语义");
    }

    @Test
    void handlesManyKeysConcurrently() throws Exception {
        KeyedLock<Integer> keyedLock = new KeyedLock<>();
        int keyCount = 32;
        CountDownLatch completed = new CountDownLatch(keyCount);
        boolean[] failed = new boolean[1];

        for (int index = 0; index < keyCount; index++) {
            int key = index;
            Thread.ofVirtual().start(() -> {
                try {
                    if (keyedLock.tryLock(key, 2, TimeUnit.SECONDS)) {
                        try {
                            Thread.sleep(5);
                        } finally {
                            keyedLock.unlock(key);
                        }
                    } else {
                        failed[0] = true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failed[0] = true;
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS), "不同 key 必须能并行获取");
        assertFalse(failed[0]);
        assertEquals(0, keyedLock.trackedKeys());
    }
}
