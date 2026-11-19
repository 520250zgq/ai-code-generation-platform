package com.zgq.zgqaicodemother.workflow.node;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 代码生成节点测试：验证成功落盘、输出目录缺失和 AI 异常处理。
 */
class HtmlCodeGeneratorNodeTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private final List<Path> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirs.forEach(path -> FileUtil.del(path.toFile()));
    }

    @Test
    void createsOutputDirectoryAndMarksSuccess() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        long appId = IDS.incrementAndGet();
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), eq(CodeGenTypeEnum.HTML), anyLong()))
                .thenAnswer(invocation -> {
                    Path dir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + appId);
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("index.html"), "<html></html>");
                    createdDirs.add(dir);
                    return Flux.just("ok");
                });
        WorkflowContext context = context(appId, CodeGenTypeEnum.HTML);

        WorkflowContext updated = run(new HtmlCodeGeneratorNode(facade, new WorkflowProperties()), context);

        assertEquals(CodeGenTypeEnum.HTML, updated.getGenerationType());
        assertTrue(updated.getGeneratedCodeDir().endsWith("html_" + appId));
        assertTrue(!updated.hasError());
        assertTrue(updated.getCurrentStep().contains("完成"));
    }

    @Test
    void missingOutputDirectoryIsFailure() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        long appId = IDS.incrementAndGet();
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), eq(CodeGenTypeEnum.HTML), anyLong()))
                .thenReturn(Flux.just("ok"));

        WorkflowContext updated = run(new HtmlCodeGeneratorNode(facade, new WorkflowProperties()),
                context(appId, CodeGenTypeEnum.HTML));

        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("输出目录"));
    }

    @Test
    void aiFailureIsRecordedNotThrown() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        long appId = IDS.incrementAndGet();
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), eq(CodeGenTypeEnum.HTML), anyLong()))
                .thenReturn(Flux.error(new RuntimeException("model unavailable")));

        WorkflowContext updated = run(new HtmlCodeGeneratorNode(facade, new WorkflowProperties()),
                context(appId, CodeGenTypeEnum.HTML));

        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("model unavailable"));
    }

    @Test
    void routingMismatchIsFailure() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        WorkflowContext context = context(IDS.incrementAndGet(), CodeGenTypeEnum.MULTI_FILE);

        WorkflowContext updated = run(new HtmlCodeGeneratorNode(facade, new WorkflowProperties()), context);

        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("不一致"));
    }

    @Test
    void stalledStreamTimesOutInsteadOfHanging() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        long appId = IDS.incrementAndGet();
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), eq(CodeGenTypeEnum.HTML), anyLong()))
                .thenReturn(Flux.never());
        WorkflowProperties properties = new WorkflowProperties();
        properties.setCodeGenerationTimeout(java.time.Duration.ofMillis(100));

        long start = System.nanoTime();
        WorkflowContext updated = run(new HtmlCodeGeneratorNode(facade, properties),
                context(appId, CodeGenTypeEnum.HTML));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(updated.hasError());
        assertTrue(elapsedMillis < 5_000, "超时必须在可控时间内返回，实际耗时 " + elapsedMillis);
    }

    private static WorkflowContext context(long appId, CodeGenTypeEnum type) {
        return WorkflowContext.builder()
                .workflowId("wf-test")
                .appId(appId)
                .originalPrompt("做一个页面")
                .enhancedPrompt("做一个页面")
                .generationType(type)
                .build();
    }

    private static WorkflowContext run(AbstractCodeGeneratorNode node, WorkflowContext context) {
        Map<String, Object> result = node.create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        return WorkflowState.get(new MessagesState<>(result));
    }
}
