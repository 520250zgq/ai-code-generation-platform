package com.zgq.zgqaicodemother.workflow;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.ai.CodeQualityCheckService;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.QualityCheckReview;
import com.zgq.zgqaicodemother.workflow.node.CodeQualityCheckNode;
import com.zgq.zgqaicodemother.workflow.node.HtmlCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ImageCollectorNode;
import com.zgq.zgqaicodemother.workflow.node.MultiFileCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ProjectBuilderNode;
import com.zgq.zgqaicodemother.workflow.node.PromptEnhancerNode;
import com.zgq.zgqaicodemother.workflow.node.RouterNode;
import com.zgq.zgqaicodemother.workflow.node.VueCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.service.ImageCollectionService;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.tools.CodeBundleTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 完整工作流图测试：使用假的图片服务和 AI 生成服务，验证条件路由、三个生成分支、代码质检、
 * 修复循环和构建节点。
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
        assertTrue(result.getQualityCheckResult().passed());
        assertEquals(0, result.getRepairAttempts());
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
    void executionFailuresStopQualityCheckAndBuildButWorkflowEndsNormally() {
        Fixture fixture = fixture(CodeGenTypeEnum.VUE_PROJECT);
        doReturn(Flux.error(new RuntimeException("model down")))
                .when(fixture.facade)
                .generateAndSaveCodeStreamForWorkflow(anyString(), any(), anyLong());

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertTrue(result.hasError());
        assertTrue(result.getErrorMessage().contains("model down"));
        assertFalse(result.isBuildSuccess());
        assertTrue(result.getCurrentStep().contains("跳过"));
        verifyNoInteractions(fixture.qualityService);
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

    @Test
    void qualityCheckFailureRepairsWithErrorsThenSucceeds() {
        Fixture fixture = fixture(CodeGenTypeEnum.HTML);
        when(fixture.qualityService.reviewCode(anyString())).thenReturn(
                review(false, List.of("index.html：body 标签未闭合")),
                review(true, List.of()));

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertFalse(result.hasError(), result.getErrorMessage());
        assertEquals(1, result.getRepairAttempts());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(fixture.facade, times(2)).generateAndSaveCodeStreamForWorkflow(
                prompts.capture(), eq(CodeGenTypeEnum.HTML), eq(fixture.appId));
        String repairPrompt = prompts.getAllValues().get(1);
        assertTrue(repairPrompt.contains("修复已有代码"), repairPrompt);
        assertTrue(repairPrompt.contains("index.html：body 标签未闭合"), repairPrompt);
        assertTrue(repairPrompt.contains("当前代码"), "HTML 修复必须携带现有代码");
        assertTrue(result.getCurrentStep().contains("无需构建"));
        assertTrue(result.getQualityCheckResult().passed());
    }

    @Test
    void qualityCheckFailureStopsAfterRepairBudget() {
        Fixture fixture = fixture(CodeGenTypeEnum.HTML);
        fixture.properties.getQualityCheck().setMaxRepairAttempts(0);
        when(fixture.qualityService.reviewCode(anyString())).thenReturn(
                review(false, List.of("index.html：body 标签未闭合")));

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertTrue(result.hasError());
        assertTrue(result.getErrorMessage().contains("body 标签未闭合"), result.getErrorMessage());
        assertFalse(result.getQualityCheckResult().passed());
        assertTrue(result.getCurrentStep().contains("已达最大修复次数"), result.getCurrentStep());
        verify(fixture.facade, times(1)).generateAndSaveCodeStreamForWorkflow(anyString(), any(), anyLong());
        verifyNoInteractions(fixture.vueBuilder);
    }

    @Test
    void vueBuildFailureRepairsWithBuildErrorThenSucceeds() {
        Fixture fixture = fixture(CodeGenTypeEnum.VUE_PROJECT);
        AtomicInteger buildAttempts = new AtomicInteger();
        when(fixture.vueBuilder.buildProject(anyString())).thenAnswer(invocation -> {
            Path dir = Path.of(invocation.getArgument(0, String.class));
            if (buildAttempts.incrementAndGet() == 1) {
                return false;
            }
            Files.createDirectories(dir.resolve("dist"));
            Files.writeString(dir.resolve("dist/index.html"), "<html></html>");
            return true;
        });

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertFalse(result.hasError(), result.getErrorMessage());
        assertTrue(result.isBuildSuccess());
        assertEquals(1, result.getRepairAttempts());
        assertEquals(2, buildAttempts.get());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(fixture.facade, times(2)).generateAndSaveCodeStreamForWorkflow(
                prompts.capture(), eq(CodeGenTypeEnum.VUE_PROJECT), eq(fixture.appId));
        assertTrue(prompts.getAllValues().get(1).contains("构建失败"),
                prompts.getAllValues().get(1));
    }

    @Test
    void disabledQualityCheckSkipsAiButStillBuilds() {
        Fixture fixture = fixture(CodeGenTypeEnum.HTML);
        fixture.properties.getQualityCheck().setEnabled(false);

        WorkflowContext result = fixture.workflow.execute(context(fixture.appId, "做一个网站"));

        assertFalse(result.hasError(), result.getErrorMessage());
        assertEquals(null, result.getQualityCheckResult());
        verifyNoInteractions(fixture.qualityService);
        assertTrue(result.getCurrentStep().contains("无需构建"));
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
                    writeProjectFiles(dir, actualType);
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

        CodeQualityCheckService qualityService = mock(CodeQualityCheckService.class);
        when(qualityService.reviewCode(anyString())).thenReturn(review(true, List.of()));

        WorkflowProperties properties = new WorkflowProperties();
        CodeBundleTool codeBundleTool = new CodeBundleTool();
        CodeGenWorkflow workflow = new CodeGenWorkflow(
                new ImageCollectorNode(collectionService),
                new PromptEnhancerNode(),
                new RouterNode(routingService),
                new HtmlCodeGeneratorNode(facade, properties, codeBundleTool),
                new MultiFileCodeGeneratorNode(facade, properties, codeBundleTool),
                new VueCodeGeneratorNode(facade, properties, codeBundleTool),
                new CodeQualityCheckNode(codeBundleTool, qualityService, properties),
                new ProjectBuilderNode(vueBuilder, properties));
        return new Fixture(workflow, facade, vueBuilder, qualityService, properties, appId);
    }

    /**
     * 写入能通过确定性静态检查的最小工程：入口文件完整、本地引用存在、Vue 具备构建必需文件。
     */
    private static void writeProjectFiles(Path dir, CodeGenTypeEnum type) throws Exception {
        Files.createDirectories(dir);
        switch (type) {
            case HTML -> Files.writeString(dir.resolve("index.html"),
                    "<!DOCTYPE html><html><body><h1>ok</h1></body></html>");
            case MULTI_FILE -> {
                Files.writeString(dir.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                        <head><link rel="stylesheet" href="style.css"></head>
                        <body><h1>ok</h1><script src="script.js"></script></body>
                        </html>
                        """);
                Files.writeString(dir.resolve("style.css"), "body { margin: 0; }");
                Files.writeString(dir.resolve("script.js"), "console.log('ok');");
            }
            case VUE_PROJECT -> {
                Files.writeString(dir.resolve("index.html"), """
                        <!DOCTYPE html>
                        <html>
                        <body>
                          <div id="app"></div>
                          <script type="module" src="/src/main.js"></script>
                        </body>
                        </html>
                        """);
                Files.writeString(dir.resolve("package.json"), """
                        {
                          "name": "demo",
                          "scripts": { "build": "vite build" },
                          "dependencies": { "vue": "^3.3.4", "vue-router": "^4.2.4" }
                        }
                        """);
                Files.createDirectories(dir.resolve("src"));
                Files.writeString(dir.resolve("src/main.js"),
                        "import { createApp } from 'vue'\nimport App from './App.vue'\ncreateApp(App).mount('#app')");
                Files.writeString(dir.resolve("src/App.vue"),
                        "<template><div>ok</div></template>");
            }
        }
    }

    private static QualityCheckReview review(boolean valid, List<String> errors) {
        return QualityCheckReview.builder()
                .valid(valid)
                .errors(errors)
                .suggestions(List.of())
                .build();
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
                           VueProjectBuilder vueBuilder, CodeQualityCheckService qualityService,
                           WorkflowProperties properties, long appId) {
    }
}
