package com.zgq.zgqaicodemother.ai.tools;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用循环保护测试：重复调用先提示、后熔断，总次数超限直接熔断。
 */
class ToolCallGuardTest {

    private static final long APP_ID = 101L;

    @Test
    void allowsCallsWhenNoTurnIsActive() {
        ToolCallGuard guard = new ToolCallGuard(properties(5, 1, 1));

        assertTrue(guard.beforeCall(APP_ID, "writeFile", "src/App.vue").allowed(),
                "没有登记轮次时不应参与统计，避免把一次性的工具调用误判成循环");
        assertEquals(0, guard.callCount(APP_ID));
    }

    @Test
    void deniesRepeatCallsWithCorrectiveHintBeforeAborting() {
        ToolCallGuard guard = new ToolCallGuard(properties(10, 2, 2));
        guard.startTurn(APP_ID);

        assertTrue(guard.beforeCall(APP_ID, "writeFile", "src/App.vue").allowed());
        assertTrue(guard.beforeCall(APP_ID, "writeFile", "src/App.vue").allowed());

        ToolCallDecision denied = guard.beforeCall(APP_ID, "writeFile", "src/App.vue");
        assertFalse(denied.allowed());
        assertTrue(denied.hint().contains("请继续创建或修改其它文件"), denied.hint());
        assertTrue(denied.hint().contains("readFile"), "提示必须给出下一步动作，模型才能自我纠正");

        // 提示两次后仍然重复，说明模型没有纠正，直接熔断本轮生成。
        assertFalse(guard.beforeCall(APP_ID, "writeFile", "src/App.vue").allowed());
        assertThrows(ToolCallGuard.ToolCallLimitException.class,
                () -> guard.beforeCall(APP_ID, "writeFile", "src/App.vue"));
    }

    @Test
    void differentSubjectsAreCountedIndependently() {
        ToolCallGuard guard = new ToolCallGuard(properties(10, 1, 1));
        guard.startTurn(APP_ID);

        assertTrue(guard.beforeCall(APP_ID, "writeFile", "src/App.vue").allowed());
        assertTrue(guard.beforeCall(APP_ID, "writeFile", "src/main.js").allowed());
        assertTrue(guard.beforeCall(APP_ID, "readFile", "src/App.vue").allowed(),
                "读和写是不同的操作，不应互相消耗重复额度");
        assertEquals(3, guard.callCount(APP_ID));
    }

    @Test
    void abortsWhenTotalCallsExceedBudget() {
        ToolCallGuard guard = new ToolCallGuard(properties(3, 5, 5));
        guard.startTurn(APP_ID);

        assertDoesNotThrow(() -> {
            guard.beforeCall(APP_ID, "writeFile", "a.vue");
            guard.beforeCall(APP_ID, "writeFile", "b.vue");
            guard.beforeCall(APP_ID, "writeFile", "c.vue");
        });

        ToolCallGuard.ToolCallLimitException error = assertThrows(
                ToolCallGuard.ToolCallLimitException.class,
                () -> guard.beforeCall(APP_ID, "writeFile", "d.vue"));
        assertTrue(error.getMessage().contains("工具调用次数超过上限"), error.getMessage());
    }

    @Test
    void finishTurnResetsBudgetAndTracking() {
        ToolCallGuard guard = new ToolCallGuard(properties(1, 1, 1));
        guard.startTurn(APP_ID);
        guard.beforeCall(APP_ID, "writeFile", "a.vue");
        assertThrows(ToolCallGuard.ToolCallLimitException.class,
                () -> guard.beforeCall(APP_ID, "writeFile", "b.vue"));

        guard.finishTurn(APP_ID);
        assertEquals(0, guard.trackedTurns());
        guard.startTurn(APP_ID);
        assertTrue(guard.beforeCall(APP_ID, "writeFile", "b.vue").allowed(),
                "新一轮生成必须重新获得完整预算");
    }

    @Test
    void disabledGuardNeverBlocks() {
        ToolCallProperties properties = properties(1, 1, 1);
        properties.setEnabled(false);
        ToolCallGuard guard = new ToolCallGuard(properties);
        guard.startTurn(APP_ID);

        for (int index = 0; index < 20; index++) {
            assertTrue(guard.beforeCall(APP_ID, "writeFile", "same.vue").allowed());
        }
        assertEquals(0, guard.trackedTurns());
    }

    @Test
    void concurrentCallsCannotExceedBudget() throws Exception {
        int budget = 20;
        ToolCallGuard guard = new ToolCallGuard(properties(budget, 100, 100));
        guard.startTurn(APP_ID);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(60);
        int[] allowedCount = new int[1];
        int[] abortedCount = new int[1];

        for (int index = 0; index < 60; index++) {
            int subject = index;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    if (guard.beforeCall(APP_ID, "writeFile", "file-" + subject + ".vue").allowed()) {
                        synchronized (allowedCount) {
                            allowedCount[0]++;
                        }
                    }
                } catch (ToolCallGuard.ToolCallLimitException e) {
                    synchronized (abortedCount) {
                        abortedCount[0]++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(budget, allowedCount[0], "并发下也不能突破单轮预算");
        assertEquals(60 - budget, abortedCount[0]);
    }

    private static ToolCallProperties properties(int maxCalls, int maxRepeats, int maxDenials) {
        ToolCallProperties properties = new ToolCallProperties();
        properties.setEnabled(true);
        properties.setMaxCallsPerTurn(maxCalls);
        properties.setMaxRepeatsPerSubject(maxRepeats);
        properties.setMaxRepeatedDenials(maxDenials);
        return properties;
    }
}
