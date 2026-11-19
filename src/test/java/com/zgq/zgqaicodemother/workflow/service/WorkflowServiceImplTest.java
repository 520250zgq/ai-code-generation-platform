package com.zgq.zgqaicodemother.workflow.service;

import cn.hutool.core.io.FileUtil;
import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.core.builder.VueProjectBuilder;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.manager.CosManager;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.workflow.CodeGenWorkflow;
import com.zgq.zgqaicodemother.workflow.config.WorkflowProperties;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.WorkflowEvent;
import com.zgq.zgqaicodemother.workflow.model.WorkflowResult;
import com.zgq.zgqaicodemother.workflow.node.HtmlCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ImageCollectorNode;
import com.zgq.zgqaicodemother.workflow.node.MultiFileCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.node.ProjectBuilderNode;
import com.zgq.zgqaicodemother.workflow.node.PromptEnhancerNode;
import com.zgq.zgqaicodemother.workflow.node.RouterNode;
import com.zgq.zgqaicodemother.workflow.node.VueCodeGeneratorNode;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.tools.ContentImageSearchTool;
import com.zgq.zgqaicodemother.workflow.tools.LogoGeneratorTool;
import com.zgq.zgqaicodemother.workflow.tools.MermaidDiagramTool;
import com.zgq.zgqaicodemother.workflow.tools.UndrawIllustrationTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流服务测试：应用归属校验、并发限制、结果路径脱敏和 SSE 事件顺序。
 */
class WorkflowServiceImplTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime());

    private AppService appService;

    private ContentImageSearchTool contentTool;

    private UndrawIllustrationTool undrawTool;

    private MermaidDiagramTool mermaidTool;

    private LogoGeneratorTool logoTool;

    private CosManager cosManager;

    private WorkflowProperties properties;

    private final List<Path> createdDirs = new ArrayList<>();

    private final User loginUser = user();

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        contentTool = mock(ContentImageSearchTool.class);
        undrawTool = mock(UndrawIllustrationTool.class);
        mermaidTool = mock(MermaidDiagramTool.class);
        logoTool = mock(LogoGeneratorTool.class);
        cosManager = mock(CosManager.class);
        properties = new WorkflowProperties();
    }

    @AfterEach
    void cleanup() {
        createdDirs.forEach(path -> FileUtil.del(path.toFile()));
    }

    @Test
    void executesWorkflowAndReturnsRelativePaths() {
        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId(), "初始化需求");
        when(appService.getById(appId)).thenReturn(app);
        CodeGenWorkflow workflow = mock(CodeGenWorkflow.class);
        when(workflow.execute(any())).thenAnswer(invocation -> {
            WorkflowContext context = invocation.getArgument(0);
            context.setGenerationType(CodeGenTypeEnum.HTML);
            context.setGeneratedCodeDir(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    "html_" + appId).toString());
            context.setBuildResultDir(context.getGeneratedCodeDir());
            context.setBuildSuccess(true);
            context.setCurrentStep("项目构建完成（静态文件无需构建）");
            return context;
        });
        WorkflowServiceImpl service = service(workflow);

        WorkflowResult result = service.execute(appId, "本次需求", loginUser);

        assertTrue(result.success());
        assertEquals("html_" + appId, result.generatedCodeDir());
        assertEquals("html_" + appId, result.buildResultDir());
        assertEquals(CodeGenTypeEnum.HTML.getValue(), result.generationType());
        assertEquals("项目构建完成（静态文件无需构建）", result.currentStep());
        verify(workflow).execute(any());
    }

    @Test
    void syncsAppCodeGenTypeWhenRouteDiffers() {
        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId(), "需求");
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        when(appService.getById(appId)).thenReturn(app);
        when(appService.updateById(any(App.class))).thenReturn(true);
        CodeGenWorkflow workflow = mock(CodeGenWorkflow.class);
        when(workflow.execute(any())).thenAnswer(invocation -> {
            WorkflowContext context = invocation.getArgument(0);
            context.setGenerationType(CodeGenTypeEnum.MULTI_FILE);
            context.setGeneratedCodeDir(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                    "multi_file_" + appId).toString());
            context.setCurrentStep("代码生成完成");
            return context;
        });
        ArgumentCaptor<App> captor = ArgumentCaptor.forClass(App.class);

        service(workflow).execute(appId, "需求", loginUser);

        verify(appService).updateById(captor.capture());
        assertEquals(CodeGenTypeEnum.MULTI_FILE.getValue(), captor.getValue().getCodeGenType());
        assertEquals(appId, captor.getValue().getId());
        assertNotNull(captor.getValue().getEditTime());
    }

    @Test
    void blankPromptFallsBackToAppInitPrompt() {
        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId(), "应用初始化需求");
        when(appService.getById(appId)).thenReturn(app);
        CodeGenWorkflow workflow = mock(CodeGenWorkflow.class);
        when(workflow.execute(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowServiceImpl service = service(workflow);
        ArgumentCaptor<WorkflowContext> captor = ArgumentCaptor.forClass(WorkflowContext.class);

        service.execute(appId, "  ", loginUser);

        verify(workflow).execute(captor.capture());
        assertEquals("应用初始化需求", captor.getValue().getOriginalPrompt());
    }

    @Test
    void rejectsOtherUsersApp() {
        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId() + 1, "需求");
        when(appService.getById(appId)).thenReturn(app);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service(mock(CodeGenWorkflow.class)).execute(appId, "需求", loginUser));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), error.getCode());
    }

    @Test
    void rejectsMissingAppAndTooLongPrompt() {
        long missingAppId = IDS.incrementAndGet();
        when(appService.getById(missingAppId)).thenReturn(null);
        WorkflowServiceImpl service = service(mock(CodeGenWorkflow.class));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(),
                assertThrows(BusinessException.class, () -> service.execute(missingAppId, "需求", loginUser)).getCode());

        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId(), "需求");
        when(appService.getById(appId)).thenReturn(app);
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(),
                assertThrows(BusinessException.class,
                        () -> service.execute(appId, "x".repeat(AppConstant.MAX_INIT_PROMPT_LENGTH + 1), loginUser)).getCode());
    }

    @Test
    void enforcesConcurrentRunLimit() throws Exception {
        properties.setMaxConcurrentRuns(1);
        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId(), "需求");
        when(appService.getById(appId)).thenReturn(app);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CodeGenWorkflow workflow = mock(CodeGenWorkflow.class);
        when(workflow.execute(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });
        WorkflowServiceImpl service = service(workflow);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> {
            try {
                service.execute(appId, "需求", loginUser);
            } catch (Throwable e) {
                firstError.set(e);
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.execute(appId, "需求", loginUser));
        assertTrue(rejected.getMessage().contains("并发已满"));
        release.countDown();
        first.join(5000);
        assertEquals(null, firstError.get());
    }

    @Test
    void streamEmitsStartNodeAndCompletedEvents() {
        long appId = IDS.incrementAndGet();
        App app = app(appId, loginUser.getId(), "初始化需求");
        when(appService.getById(appId)).thenReturn(app);
        CodeGenWorkflow workflow = realWorkflow(appId, CodeGenTypeEnum.HTML);
        WorkflowServiceImpl service = service(workflow);

        List<WorkflowEvent> events = service.executeStream(appId, "做一个页面", loginUser)
                .collectList()
                .block(Duration.ofSeconds(20));

        assertTrue(events != null && !events.isEmpty());
        assertEquals(WorkflowEvent.TYPE_START, events.get(0).type());
        assertTrue(events.stream().anyMatch(e -> WorkflowEvent.TYPE_NODE_COMPLETED.equals(e.type())));
        WorkflowEvent last = events.get(events.size() - 1);
        assertEquals(WorkflowEvent.TYPE_COMPLETED, last.type());
        assertTrue(last.result().success());
        assertEquals("html_" + appId, last.result().generatedCodeDir());
        assertFalse(last.result().warnings().contains("图片收集节点异常"));
    }

    @Test
    void statusDoesNotExposeSecrets() {
        when(contentTool.configuredProvider()).thenReturn("auto");
        when(contentTool.describeAvailability()).thenReturn(Map.of("pexels", true, "picsum", true));
        when(undrawTool.available()).thenReturn(true);
        when(mermaidTool.available()).thenReturn(true);
        when(logoTool.available()).thenReturn(false);
        when(cosManager.isAvailable()).thenReturn(true);
        WorkflowServiceImpl service = service(mock(CodeGenWorkflow.class));

        Map<String, Object> status = service.status();

        assertEquals(true, status.get("enabled"));
        assertTrue(status.containsKey("contentImage"));
        assertTrue(status.containsKey("nodes"));
        String text = status.toString();
        assertFalse(text.contains("apiKey"));
        assertFalse(text.contains("secret"));
    }

    private WorkflowServiceImpl service(CodeGenWorkflow workflow) {
        return new WorkflowServiceImpl(workflow, appService, properties,
                contentTool, undrawTool, mermaidTool, logoTool, cosManager);
    }

    private CodeGenWorkflow realWorkflow(long appId, CodeGenTypeEnum type) {
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
                    Path dir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR,
                            actualType.getValue() + "_" + appId).toAbsolutePath().normalize();
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("index.html"), "<html></html>");
                    if (actualType == CodeGenTypeEnum.VUE_PROJECT) {
                        Files.writeString(dir.resolve("package.json"), "{}");
                    }
                    createdDirs.add(dir);
                    return Flux.just("ok");
                });
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        when(builder.buildProject(anyString())).thenAnswer(invocation -> {
            String projectPath = invocation.getArgument(0);
            Path dir = Path.of(projectPath);
            Files.createDirectories(dir.resolve("dist"));
            return true;
        });
        WorkflowProperties workflowProperties = new WorkflowProperties();
        return new CodeGenWorkflow(
                new ImageCollectorNode(collectionService),
                new PromptEnhancerNode(),
                new RouterNode(routingService),
                new HtmlCodeGeneratorNode(facade, workflowProperties),
                new MultiFileCodeGeneratorNode(facade, workflowProperties),
                new VueCodeGeneratorNode(facade, workflowProperties),
                new ProjectBuilderNode(builder));
    }

    private static App app(long id, long userId, String prompt) {
        return App.builder()
                .id(id)
                .userId(userId)
                .initPrompt(prompt)
                .build();
    }

    private static User user() {
        User user = new User();
        user.setId(1L);
        return user;
    }
}
