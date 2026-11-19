package com.zgq.zgqaicodemother.workflow;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.node.HtmlCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ImageCollectorNode;
import com.zgq.zgqaicodemother.workflow.node.MultiFileCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ProjectBuilderNode;
import com.zgq.zgqaicodemother.workflow.node.PromptEnhancerNode;
import com.zgq.zgqaicodemother.workflow.node.RouterNode;
import com.zgq.zgqaicodemother.workflow.node.VueCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.service.ImageCollectionService;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 完整工作流图测试：使用假的图片服务和 AI 生成服务，验证条件路由、三个生成分支和构建节点。
 */
class CodeGenWorkflowTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private final List<Path> createdDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        createdDirs.forEach(path -> FileUtil.del(path.toFile()));
    }

    @ParameterizedTest
    @EnumSource(CodeGenTypeEnum.class)
    void routesToExpectedGeneratorAndBuilds(CodeGenTypeEnum type) {
        Fixture fixture = fixture(type);

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertEquals(type, result.getGenerationType());
        assertTrue(result.getGeneratedCodeDir().endsWith(type.getValue() + "_" + fixture.appId));
        assertFalse(result.hasError(), result.getErrorMessage());
        if (type == CodeGenTypeEnum.VUE_PROJECT) {
            assertTrue(result.getBuildResultDir().endsWith("dist"));
            assertTrue(result.getCurrentStep().contains("Vue"));
            verify(fixture.vueBuilder).buildProject(anyString());
        } else {
            assertTrue(result.getCurrentStep().contains("无需构建"));
        }
        verify(fixture.facade).generateAndSaveCodeStreamForWorkflow(anyString(), eq(type), eq(fixture.appId));
    }

    @Test
    void graphRepresentationContainsAllNodes() {
        Fixture fixture = fixture(CodeGenTypeEnum.HTML);

        String mermaid = fixture.workflow.mermaidGraph();

        for (String node : WorkflowNodes.ORDERED_NODES) {
            assertTrue(mermaid.contains(node), "缺少节点 " + node + "\n" + mermaid);
        }
    }

    @Test
    void executionFailuresStopBeforeBuildButWorkflowEndsNormally() {
        Fixture fixture = fixture(CodeGenTypeEnum.VUE_PROJECT);
        doReturn(Flux.error(new RuntimeException("model down")))
                .when(fixture.facade)
                .generateAndSaveCodeStreamForWorkflow(anyString(), any(), anyLong());

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertTrue(result.hasError());
        assertTrue(result.getErrorMessage().contains("model down"));
        assertFalse(result.isBuildSuccess());
        assertTrue(result.getCurrentStep().contains("跳过"));
    }

    @Test
    void compiledGraphCanBeReusedAcrossRuns() {
        Fixture fixture = fixture(CodeGenTypeEnum.HTML);

        WorkflowContext first = fixture.workflow.execute(context(fixture.appId, "第一个需求"));
        WorkflowContext second = fixture.workflow.execute(context(fixture.appId, "第二个需求"));

        assertFalse(first.hasError());
        assertFalse(second.hasError());
        assertEquals(first.getAppId(), second.getAppId());
        assertTrue(fixture.workflow.mermaidGraph().contains(WorkflowNodes.ROUTER));
    }

    private Fixture fixture(CodeGenTypeEnum type) {
        long appId = IDS.incrementAndGet();
        ImageCollectionService collectionService = mock(ImageCollectionService.class);
        when(collectionService.collect(anyString())).thenReturn(
                new ImageCollectionService.ImageCollectionOutcome(
                        ImageCollectionPlan.builder().build(), List.of(), List.of()));
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        when(routingService.routeCodeGenType(anyString())).thenReturn(type);

        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        when(facade.generateAndSaveCodeStreamForWorkflow(anyString(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    CodeGenTypeEnum actualType = invocation.getArgument(1);
                    Long actualAppId = invocation.getArgument(2);
                    Path dir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                            actualType.getValue() + "_" + actualAppId).toAbsolutePath().normalize();
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("index.html"), "<html></html>");
                    if (actualType == CodeGenTypeEnum.VUE_PROJECT) {
                        Files.writeString(dir.resolve("package.json"), "{}");
                    }
                    createdDirs.add(dir);
                    return Flux.just("ok");
                });

        VueProjectBuilder vueBuilder = mock(VueProjectBuilder.class);
        when(vueBuilder.buildProject(anyString())).thenAnswer(invocation -> {
            String projectPath = invocation.getArgument(0);
            Path dir = Path.of(projectPath);
            Files.createDirectories(dir.resolve("dist"));
            Files.writeString(dir.resolve("dist/index.html"), "<html></html>");
            return true;
        });

        WorkflowProperties properties = new WorkflowProperties();
        CodeGenWorkflow workflow = new CodeGenWorkflow(
                new ImageCollectorNode(collectionService),
                new PromptEnhancerNode(),
                new RouterNode(routingService),
                new HtmlCodeGeneratorNode(facade, properties),
                new MultiFileCodeGeneratorNode(facade, properties),
                new VueCodeGeneratorNode(facade, properties),
                new ProjectBuilderNode(vueBuilder));
        return new Fixture(workflow, facade, vueBuilder, appId);
    }

    private static WorkflowContext context(long appId, String prompt) {
        return WorkflowContext.builder()
                .workflowId(UUID.randomUUID().toString())
                .appId(appId)
                .userId(1L)
                .originalPrompt(prompt)
                .currentStep("初始化")
                .startedAt(java.time.LocalDateTime.now())
                .build();
    }

    private record Fixture(CodeGenWorkflow workflow, AiCodeGeneratorFacade facade,
                           VueProjectBuilder vueBuilder, long appId) {
    }
}
