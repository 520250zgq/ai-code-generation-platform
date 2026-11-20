package com.zgq.zgqaicodemother.workflow.node;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目构建节点的静态站点跳过、Vue 构建成功/失败和路径校验测试。
 */
class ProjectBuilderNodeTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private final List<Path> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirs.forEach(path -> FileUtil.del(path.toFile()));
    }

    @Test
    void staticSiteSkipsVueBuilder() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Path dir = createDir("html_" + IDS.incrementAndGet());
        WorkflowContext context = context(CodeGenTypeEnum.HTML, dir);

        WorkflowContext updated = run(new ProjectBuilderNode(builder, new WorkflowProperties()), context);

        assertTrue(updated.isBuildSuccess());
        assertTrue(updated.getBuildResultDir().endsWith(dir.getFileName().toString()));
        assertTrue(updated.getCurrentStep().contains("无需构建"));
        verifyNoInteractions(builder);
    }

    @Test
    void vueBuildSuccessUsesDistDirectory() throws Exception {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Path dir = createDir("vue_project_" + IDS.incrementAndGet());
        Files.writeString(dir.resolve("package.json"), "{}");
        when(builder.buildProject(dir.toString())).thenAnswer(invocation -> {
            Files.createDirectories(dir.resolve("dist"));
            Files.writeString(dir.resolve("dist/index.html"), "<html></html>");
            return true;
        });
        WorkflowContext context = context(CodeGenTypeEnum.VUE_PROJECT, dir);

        WorkflowContext updated = run(new ProjectBuilderNode(builder, new WorkflowProperties()), context);

        assertTrue(updated.isBuildSuccess());
        assertTrue(updated.getBuildResultDir().endsWith("dist"));
        assertTrue(updated.getCurrentStep().contains("构建完成"));
        verify(builder).buildProject(dir.toString());
    }

    @Test
    void vueBuildFailureRecordsRepairInsteadOfBlockingError() throws Exception {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Path dir = createDir("vue_project_" + IDS.incrementAndGet());
        Files.writeString(dir.resolve("package.json"), "{}");
        when(builder.buildProject(anyString())).thenReturn(false);
        WorkflowContext context = context(CodeGenTypeEnum.VUE_PROJECT, dir);

        WorkflowContext updated = run(new ProjectBuilderNode(builder, new WorkflowProperties()), context);

        assertFalse(updated.isBuildSuccess());
        assertFalse(updated.hasError(), "还有修复额度时不能直接阻断工作流");
        assertNotNull(updated.getBuildFailureMessage());
        assertTrue(updated.getBuildFailureMessage().contains("构建失败"));
        assertEquals(1, updated.getRepairAttempts());
        assertTrue(updated.getCurrentStep().contains("准备进入质检修复"));
    }

    @Test
    void vueBuildFailureWithoutRepairBudgetIsBlockingError() throws Exception {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        Path dir = createDir("vue_project_" + IDS.incrementAndGet());
        Files.writeString(dir.resolve("package.json"), "{}");
        when(builder.buildProject(anyString())).thenReturn(false);
        WorkflowProperties properties = new WorkflowProperties();
        properties.getQualityCheck().setMaxRepairAttempts(0);
        WorkflowContext context = context(CodeGenTypeEnum.VUE_PROJECT, dir);

        WorkflowContext updated = run(new ProjectBuilderNode(builder, properties), context);

        assertFalse(updated.isBuildSuccess());
        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("构建失败"));
    }

    @Test
    void rejectsDirectoryOutsideCodeOutputRoot() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        WorkflowContext context = context(CodeGenTypeEnum.HTML, Path.of(System.getProperty("java.io.tmpdir")));

        WorkflowContext updated = run(new ProjectBuilderNode(builder, new WorkflowProperties()), context);

        assertTrue(updated.hasError());
        assertTrue(updated.getErrorMessage().contains("路径不合法"));
        verifyNoInteractions(builder);
    }

    @Test
    void upstreamErrorSkipsBuild() {
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        WorkflowContext context = context(CodeGenTypeEnum.VUE_PROJECT,
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "not_exists"));
        context.fail("代码生成失败", "上游错误");

        WorkflowContext updated = run(new ProjectBuilderNode(builder, new WorkflowProperties()), context);

        assertEquals("项目构建跳过（上游失败）", updated.getCurrentStep());
        verifyNoInteractions(builder);
    }

    private Path createDir(String name) {
        Path dir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, name).toAbsolutePath().normalize();
        FileUtil.mkdir(dir.toFile());
        createdDirs.add(dir);
        return dir;
    }

    private static WorkflowContext context(CodeGenTypeEnum type, Path dir) {
        return WorkflowContext.builder()
                .workflowId("wf-test")
                .appId(1L)
                .generationType(type)
                .generatedCodeDir(dir.toString())
                .build();
    }

    private static WorkflowContext run(ProjectBuilderNode node, WorkflowContext context) {
        Map<String, Object> result = node.create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        return WorkflowState.get(new MessagesState<>(result));
    }
}
