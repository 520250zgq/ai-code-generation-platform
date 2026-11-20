package com.zgq.zgqaicodemother.workflow.node;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.ai.CodeQualityCheckService;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.QualityCheckResult;
import com.zgq.zgqaicodemother.workflow.model.QualityCheckReview;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import com.zgq.zgqaicodemother.workflow.tools.CodeBundleTool;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 代码质检节点测试：通过、修复、额度耗尽、AI 降级、上游失败和开关关闭。
 */
class CodeQualityCheckNodeTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private final List<Path> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirs.forEach(path -> FileUtil.del(path.toFile()));
    }

    @Test
    void passingQualityCheckContinuesToBuild() throws Exception {
        Path dir = codeDir();
        writeValidHtml(dir);
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.reviewCode(anyString())).thenReturn(review(true));

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context(dir));

        QualityCheckResult result = updated.getQualityCheckResult();
        assertNotNull(result);
        assertTrue(result.passed(), result.getErrors().toString());
        assertEquals(List.of("index.html"), result.getCheckedFiles());
        assertEquals("static+ai", result.getSource());
        assertEquals(0, updated.getRepairAttempts());
        assertFalse(updated.hasError());
        assertTrue(updated.getCurrentStep().contains("通过"), updated.getCurrentStep());
    }

    @Test
    void failedQualityCheckRequestsRepairWithErrors() throws Exception {
        Path dir = codeDir();
        writeValidHtml(dir);
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.reviewCode(anyString())).thenReturn(review(false,
                List.of("index.html：body 标签未闭合")));

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context(dir));

        QualityCheckResult result = updated.getQualityCheckResult();
        assertFalse(result.passed());
        assertEquals(List.of("index.html：body 标签未闭合"), result.getErrors());
        assertEquals(1, updated.getRepairAttempts());
        assertFalse(updated.hasError(), "还有额度时不能阻断工作流");
        assertTrue(updated.getCurrentStep().contains("返回生成节点修复"), updated.getCurrentStep());
    }

    @Test
    void exhaustedRepairBudgetFailsWorkflow() throws Exception {
        Path dir = codeDir();
        writeValidHtml(dir);
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.reviewCode(anyString())).thenReturn(review(false, List.of("脚本语法错误")));
        WorkflowProperties properties = new WorkflowProperties();
        properties.getQualityCheck().setMaxRepairAttempts(0);

        WorkflowContext updated = run(node(service, properties), context(dir));

        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("脚本语法错误"), updated.getErrorMessage());
        assertTrue(updated.getCurrentStep().contains("未通过"), updated.getCurrentStep());
        assertTrue(updated.getCurrentStep().contains("已达最大修复次数"), updated.getCurrentStep());
    }

    @Test
    void staticErrorsRejectEvenWhenAiSaysValid() throws Exception {
        Path dir = codeDir();
        Files.writeString(dir.resolve("index.html"), "<html><body>截断");
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.reviewCode(anyString())).thenReturn(review(true));

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context(dir));

        QualityCheckResult result = updated.getQualityCheckResult();
        assertFalse(result.passed());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("</html>")),
                result.getErrors().toString());
        assertEquals(1, updated.getRepairAttempts());
    }

    @Test
    void aiFailureDegradesToStaticCheck() throws Exception {
        Path dir = codeDir();
        writeValidHtml(dir);
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        when(service.reviewCode(anyString())).thenThrow(new RuntimeException("model timeout"));

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context(dir));

        QualityCheckResult result = updated.getQualityCheckResult();
        assertTrue(result.passed(), result.getErrors().toString());
        assertEquals("static", result.getSource());
        assertTrue(updated.getWarnings().stream().anyMatch(warning -> warning.contains("降级为静态检查")),
                updated.getWarnings().toString());
        assertFalse(updated.hasError());
    }

    @Test
    void upstreamErrorSkipsQualityCheck() throws Exception {
        Path dir = codeDir();
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        WorkflowContext context = context(dir);
        context.fail("代码生成失败", "model down");

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context);

        assertEquals("代码质检跳过（上游失败）", updated.getCurrentStep());
        assertNull(updated.getQualityCheckResult());
        verifyNoInteractions(service);
    }

    @Test
    void invalidCodeDirectoryFailsWorkflow() {
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        WorkflowContext context = WorkflowContext.builder()
                .workflowId("wf-bad-dir")
                .generationType(CodeGenTypeEnum.HTML)
                .generatedCodeDir(Path.of(System.getProperty("java.io.tmpdir")).toString())
                .build();

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context);

        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("路径不合法"), updated.getErrorMessage());
        verifyNoInteractions(service);
    }

    @Test
    void disabledQualityCheckSkipsAiAndResult() throws Exception {
        Path dir = codeDir();
        writeValidHtml(dir);
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getQualityCheck().setEnabled(false);

        WorkflowContext updated = run(node(service, properties), context(dir));

        assertNull(updated.getQualityCheckResult());
        assertEquals("代码质检已关闭", updated.getCurrentStep());
        verifyNoInteractions(service);
    }

    @Test
    void emptyCodeDirectoryRequestsRepair() throws Exception {
        Path dir = codeDir();
        CodeQualityCheckService service = mock(CodeQualityCheckService.class);

        WorkflowContext updated = run(node(service, new WorkflowProperties()), context(dir));

        assertFalse(updated.getQualityCheckResult().passed());
        assertTrue(updated.getQualityCheckResult().getErrors().stream()
                .anyMatch(error -> error.contains("未找到可检查的代码文件")));
        assertEquals(1, updated.getRepairAttempts());
        verifyNoInteractions(service);
    }

    private CodeQualityCheckNode node(CodeQualityCheckService service, WorkflowProperties properties) {
        return new CodeQualityCheckNode(new CodeBundleTool(), service, properties);
    }

    private static QualityCheckReview review(boolean valid, List<String> errors) {
        return QualityCheckReview.builder()
                .valid(valid)
                .errors(errors)
                .suggestions(List.of("建议补充语义化标签"))
                .build();
    }

    private static QualityCheckReview review(boolean valid) {
        return review(valid, List.of());
    }

    private static void writeValidHtml(Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"),
                "<!DOCTYPE html><html><body><h1>ok</h1></body></html>");
    }

    private Path codeDir() throws Exception {
        Path dir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "check_" + IDS.incrementAndGet())
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        createdDirs.add(dir);
        return dir;
    }

    private static WorkflowContext context(Path dir) {
        return WorkflowContext.builder()
                .workflowId("wf-check")
                .appId(1L)
                .originalPrompt("做一个页面")
                .enhancedPrompt("做一个页面")
                .generationType(CodeGenTypeEnum.HTML)
                .generatedCodeDir(dir.toString())
                .build();
    }

    private static WorkflowContext run(CodeQualityCheckNode node, WorkflowContext context) {
        Map<String, Object> result = node.create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        return WorkflowState.get(new MessagesState<>(result));
    }
}
