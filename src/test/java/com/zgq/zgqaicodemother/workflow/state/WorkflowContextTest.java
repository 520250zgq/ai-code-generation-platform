package com.zgq.zgqaicodemother.workflow.state;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流上下文的状态读写和边界测试。
 */
class WorkflowContextTest {

    @Test
    void defaultsAreMutableCollections() {
        WorkflowContext context = WorkflowContext.builder().build();

        context.addWarning("first warning");
        context.setImages(List.of());

        assertEquals(List.of("first warning"), context.getWarnings());
        assertEquals(0, context.imageCount());
    }

    @Test
    void warningListIsCappedAndTrimmed() {
        WorkflowContext context = WorkflowContext.builder().build();
        for (int i = 0; i < 40; i++) {
            context.addWarning("warning-" + i + "\n" + "x".repeat(600));
        }

        assertEquals(30, context.getWarnings().size());
        assertFalse(context.getWarnings().get(0).contains("\n"));
        assertTrue(context.getWarnings().get(0).length() <= 500);
    }

    @Test
    void keepsFirstErrorAndMarksFailedStep() {
        WorkflowContext context = WorkflowContext.builder().currentStep("初始化").build();

        context.fail("代码生成失败", "第一个错误");
        context.fail("项目构建失败", "第二个错误");

        assertEquals("项目构建失败", context.getCurrentStep());
        assertEquals("第一个错误", context.getErrorMessage());
        assertTrue(context.hasError());
    }

    @Test
    void calculatesElapsedMillisAndFinishTime() {
        WorkflowContext context = WorkflowContext.builder()
                .startedAt(LocalDateTime.now().minusSeconds(2))
                .build();

        context.finish();

        assertTrue(context.getFinishedAt() != null);
        assertTrue(context.elapsedMillis() >= 1000);
    }

    @Test
    void blankWarningIsIgnored() {
        WorkflowContext context = WorkflowContext.builder().build();

        context.addWarning(" ");
        context.addWarning(null);

        assertTrue(context.getWarnings().isEmpty());
    }
}
