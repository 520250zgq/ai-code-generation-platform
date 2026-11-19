package com.zgq.zgqaicodemother.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import com.mybatisflex.core.dialect.DbType;
import com.mybatisflex.core.logicdelete.LogicDeleteManager;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.ai.memory.ConversationMemoryCache;
import com.zgq.zgqaicodemother.constant.AppConstant;
import com.zgq.zgqaicodemother.core.AiCodeGeneratorFacade;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.dto.app.AppAddRequest;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import com.zgq.zgqaicodemother.model.vo.UserVO;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import com.zgq.zgqaicodemother.service.ScreenshotService;
import com.zgq.zgqaicodemother.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private ChatHistoryService chatHistoryService;

    @Mock
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Mock
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Mock
    private ConversationMemoryCache conversationMemoryCache;

    @Mock
    private ScreenshotService screenshotService;

    @Spy
    @InjectMocks
    private AppServiceImpl appService;

    @Test
    void createAppRoutesPromptAndUsesServerDefaults() {
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("  创建一个展示摄影作品和个人简介的网站  ");
        User loginUser = new User();
        loginUser.setId(123L);
        when(aiCodeGenTypeRoutingService.routeCodeGenType(request.getInitPrompt().trim()))
                .thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            assertAll(
                    () -> assertEquals("创建一个展示摄影作品和个", app.getAppName()),
                    () -> assertEquals(request.getInitPrompt().trim(), app.getInitPrompt()),
                    () -> assertEquals(123L, app.getUserId()),
                    () -> assertEquals(AppConstant.DEFAULT_PRIORITY, app.getPriority()),
                    () -> assertEquals(CodeGenTypeEnum.VUE_PROJECT.getValue(), app.getCodeGenType()),
                    () -> assertNull(app.getCover()),
                    () -> assertNull(app.getDeployKey())
            );
            app.setId(456L);
            return true;
        }).when(appService).save(any(App.class));

        assertEquals(456L, appService.addApp(request, loginUser));
        verify(aiCodeGenTypeRoutingService).routeCodeGenType("创建一个展示摄影作品和个人简介的网站");
    }

    @Test
    void createAppFallsBackToHtmlWhenRoutingFails() {
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建一个复杂的数据管理后台");
        User loginUser = new User();
        loginUser.setId(123L);
        when(aiCodeGenTypeRoutingService.routeCodeGenType(request.getInitPrompt()))
                .thenThrow(new IllegalStateException("routing model timeout"));
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            app.setId(789L);
            assertEquals(CodeGenTypeEnum.HTML.getValue(), app.getCodeGenType());
            return true;
        }).when(appService).save(any(App.class));

        assertEquals(789L, appService.addApp(request, loginUser));
    }

    @Test
    void createAppFallsBackToHtmlWhenRoutingReturnsNull() {
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建一个个人博客");
        User loginUser = new User();
        loginUser.setId(123L);
        when(aiCodeGenTypeRoutingService.routeCodeGenType(request.getInitPrompt())).thenReturn(null);
        doAnswer(invocation -> {
            App app = invocation.getArgument(0);
            app.setId(790L);
            assertEquals(CodeGenTypeEnum.HTML.getValue(), app.getCodeGenType());
            return true;
        }).when(appService).save(any(App.class));

        assertEquals(790L, appService.addApp(request, loginUser));
    }

    @Test
    void rejectBlankOrExcessivePromptBeforeSaving() {
        User loginUser = new User();
        loginUser.setId(123L);
        for (String prompt : new String[]{null, "", " \n\t ", "x".repeat(AppConstant.MAX_INIT_PROMPT_LENGTH + 1)}) {
            AppAddRequest request = new AppAddRequest();
            request.setInitPrompt(prompt);
            assertThrows(BusinessException.class, () -> appService.addApp(request, loginUser));
        }
        verify(appService, never()).save(any(App.class));
        verifyNoInteractions(aiCodeGenTypeRoutingService);
    }

    @Test
    void rejectMissingLoginAndPersistenceFailure() {
        AppAddRequest request = new AppAddRequest();
        request.setInitPrompt("创建个人网站");
        assertThrows(BusinessException.class, () -> appService.addApp(request, null));
        User loginUser = new User();
        loginUser.setId(123L);
        doReturn(false).when(appService).save(any(App.class));
        assertThrows(BusinessException.class, () -> appService.addApp(request, loginUser));
    }

    @Test
    void chatStoresUserAndSuccessfulAiMessagesAfterSubscription() {
        Long appId = 888L;
        User loginUser = new User();
        loginUser.setId(42L);
        App app = new App();
        app.setId(appId);
        app.setUserId(loginUser.getId());
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        doReturn(app).when(appService).getById(appId);
        when(aiCodeGeneratorFacade.generateAndSaveCodeStream("build a page", CodeGenTypeEnum.HTML, appId))
                .thenReturn(Flux.just("part-1", "part-2"));

        assertEquals(List.of("part-1", "part-2"),
                appService.chatToGenCode(appId, "build a page", loginUser).collectList().block());

        verify(conversationMemoryCache).get(appId);
        verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.USER, "build a page");
        verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.AI, "part-1part-2");
    }

    @Test
    void initializesMemoryBeforeSavingCurrentUserMessage() {
        Long appId = 891L;
        User loginUser = new User();
        loginUser.setId(45L);
        App app = new App();
        app.setId(appId);
        app.setUserId(loginUser.getId());
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        doReturn(app).when(appService).getById(appId);
        when(aiCodeGeneratorFacade.generateAndSaveCodeStream("hello", CodeGenTypeEnum.HTML, appId))
                .thenReturn(Flux.just("ok"));

        appService.chatToGenCode(appId, "hello", loginUser).blockLast();

        InOrder order = inOrder(conversationMemoryCache, chatHistoryService);
        order.verify(conversationMemoryCache).get(appId);
        order.verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.USER, "hello");
    }

    @Test
    void chatStoresAiFailureDetailsWhenGenerationFails() {
        Long appId = 889L;
        User loginUser = new User();
        loginUser.setId(43L);
        App app = new App();
        app.setId(appId);
        app.setUserId(loginUser.getId());
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        doReturn(app).when(appService).getById(appId);
        when(aiCodeGeneratorFacade.generateAndSaveCodeStream("fail please", CodeGenTypeEnum.HTML, appId))
                .thenReturn(Flux.error(new IllegalStateException("model unavailable")));

        assertThrows(IllegalStateException.class,
                () -> appService.chatToGenCode(appId, "fail please", loginUser).blockLast());

        verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.USER, "fail please");
        verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.AI, "AI 回复失败：model unavailable");
        verify(conversationMemoryCache).evict(appId);
    }

    @Test
    void chatStoresAiFailureDetailsWhenFacadeThrowsSynchronously() {
        Long appId = 890L;
        User loginUser = new User();
        loginUser.setId(44L);
        App app = new App();
        app.setId(appId);
        app.setUserId(loginUser.getId());
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        doReturn(app).when(appService).getById(appId);
        when(aiCodeGeneratorFacade.generateAndSaveCodeStream("sync fail", CodeGenTypeEnum.HTML, appId))
                .thenThrow(new IllegalStateException("stream init failed"));

        assertThrows(IllegalStateException.class,
                () -> appService.chatToGenCode(appId, "sync fail", loginUser).blockLast());

        verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.USER, "sync fail");
        verify(chatHistoryService).saveMessage(appId, loginUser.getId(),
                ChatHistoryMessageTypeEnum.AI, "AI 回复失败：stream init failed");
        verify(conversationMemoryCache).evict(appId);
    }

    @Test
    void deletingAppAlsoDeletesItsHistory() {
        doReturn(true).when(appService).removeById(555L);

        assertTrue(appService.removeAppWithChatHistory(555L));

        verify(chatHistoryService).removeByAppId(555L);
        verify(appService).removeById(555L);
        verify(conversationMemoryCache).evict(555L);
    }

    @Test
    void validateOnlyProvidedUpdateFields() {
        App app = new App();
        app.setPriority(-1);
        appService.validApp(app, false);
        app.setAppName(" \t");
        assertThrows(BusinessException.class, () -> appService.validApp(app, false));
        app.setAppName("x".repeat(AppConstant.MAX_APP_NAME_LENGTH + 1));
        assertThrows(BusinessException.class, () -> appService.validApp(app, false));
        app.setAppName("新名称");
        app.setCover("x".repeat(AppConstant.MAX_COVER_LENGTH + 1));
        assertThrows(BusinessException.class, () -> appService.validApp(app, false));
        app.setCover("");
        appService.validApp(app, false);
    }

    @Test
    void includeEveryNonTimeFieldInAdminQuery() {
        AppQueryRequest request = new AppQueryRequest();
        request.setId(123L);
        request.setAppName("dashboard");
        request.setCover("https://example.com/cover.png");
        request.setInitPrompt("analytics");
        request.setCodeGenType("html");
        request.setDeployKey("deploy-1");
        request.setPriority(99);
        request.setUserId(456L);
        request.setIsDelete(1);
        request.setSortField("priority");
        request.setSortOrder("ascend");

        String sql = toSql(appService.getQueryWrapper(request));

        assertAll(
                () -> assertTrue(sql.contains("id = 123"), sql),
                () -> assertTrue(sql.contains("appName LIKE '%dashboard%'"), sql),
                () -> assertTrue(sql.contains("cover = 'https://example.com/cover.png'"), sql),
                () -> assertTrue(sql.contains("initPrompt LIKE '%analytics%'"), sql),
                () -> assertTrue(sql.contains("codeGenType = 'html'"), sql),
                () -> assertTrue(sql.contains("deployKey = 'deploy-1'"), sql),
                () -> assertTrue(sql.contains("priority = 99"), sql),
                () -> assertTrue(sql.contains("userId = 456"), sql),
                () -> assertTrue(sql.contains("isDelete = 1"), sql),
                () -> assertTrue(sql.contains("ORDER BY priority ASC, id DESC"), sql)
        );
    }

    @Test
    void ignoreBlankFiltersAndUseStableDefaultSort() {
        AppQueryRequest request = new AppQueryRequest();
        request.setAppName(" \t ");
        request.setInitPrompt("\n");

        String sql = toSql(appService.getQueryWrapper(request));

        assertFalse(sql.contains("WHERE"), sql);
        assertTrue(sql.contains("ORDER BY createTime DESC, id DESC"), sql);
    }

    @Test
    void allowExactQueryForEmptyOptionalFields() {
        AppQueryRequest request = new AppQueryRequest();
        request.setCover("");
        request.setCodeGenType("");
        request.setDeployKey("");

        String sql = toSql(appService.getQueryWrapper(request));

        assertTrue(sql.contains("cover = ''"), sql);
        assertTrue(sql.contains("codeGenType = ''"), sql);
        assertTrue(sql.contains("deployKey = ''"), sql);
    }

    @Test
    void rejectUnsafeSortAndInvalidDeleteFlag() {
        for (String field : new String[]{"id desc; drop table app", "unknown", "createTime DESC"}) {
            AppQueryRequest request = new AppQueryRequest();
            request.setSortField(field);
            assertThrows(BusinessException.class, () -> appService.getQueryWrapper(request));
        }
        AppQueryRequest request = new AppQueryRequest();
        request.setSortOrder("ascend; drop table app");
        assertThrows(BusinessException.class, () -> appService.getQueryWrapper(request));
        request.setSortOrder("descend");
        request.setIsDelete(2);
        assertThrows(BusinessException.class, () -> appService.getQueryWrapper(request));
    }

    @Test
    void exposeReadableCodeGenTypeTextInAppView() {
        App app = App.builder()
                .id(990L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();

        AppVO view = appService.getAppVO(app);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT.getValue(), view.getCodeGenType());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT.getText(), view.getCodeGenTypeText());
    }

    @Test
    void preserveRecordOrderAndBatchFetchCreators() {
        App first = App.builder().id(3L).appName("first").userId(100L).build();
        App second = App.builder().id(1L).appName("second").userId(100L).build();
        App missingCreator = App.builder().id(2L).userId(200L).build();
        User creator = new User();
        creator.setId(100L);
        UserVO creatorVO = new UserVO();
        creatorVO.setId(100L);
        creatorVO.setUserName("创建人");
        when(userService.listByIds(Set.of(100L, 200L))).thenReturn(List.of(creator));
        when(userService.getUserVO(creator)).thenReturn(creatorVO);

        List<AppVO> result = appService.getAppVOList(List.of(first, second, missingCreator));

        assertEquals(List.of(3L, 1L, 2L), result.stream().map(AppVO::getId).toList());
        assertSame(creatorVO, result.get(0).getUser());
        assertSame(creatorVO, result.get(1).getUser());
        assertNull(result.get(2).getUser());
        verify(userService).listByIds(Set.of(100L, 200L));
        verify(userService, never()).getById(any());
    }

    @Test
    void preservePageMetadataAndAvoidFetchingEmptyCreators() {
        Page<App> page = new Page<>(3, 20, 81);
        page.setRecords(List.of(App.builder().id(123L).appName("无创建人").build()));

        Page<AppVO> result = appService.getAppVOPage(page);

        assertAll(
                () -> assertEquals(3L, result.getPageNumber()),
                () -> assertEquals(20L, result.getPageSize()),
                () -> assertEquals(81L, result.getTotalRow()),
                () -> assertEquals(page.getTotalPage(), result.getTotalPage()),
                () -> assertEquals(123L, result.getRecords().getFirst().getId()),
                () -> assertNull(result.getRecords().getFirst().getUser()),
                () -> assertTrue(appService.getAppVOList(null).isEmpty()),
                () -> assertTrue(appService.getAppVOList(List.of()).isEmpty())
        );
        verifyNoInteractions(userService);
    }

    @Test
    void listAllowsLargeAdminPageAndPreservesNormalLogicDelete() {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(2);
        request.setPageSize(200);
        doAnswer(invocation -> {
            Page<App> page = invocation.getArgument(0);
            assertEquals(2L, page.getPageNumber());
            assertEquals(200L, page.getPageSize());
            assertEquals("isDelete", LogicDeleteManager.getLogicDeleteColumn("isDelete"));
            return page;
        }).when(appService).page(ArgumentMatchers.<Page<App>>any(), any(QueryWrapper.class));

        assertNotNull(appService.listAppByPage(request));
    }

    @Test
    void explicitDeleteFilterTemporarilyBypassesLogicDeleteEvenOnFailure() {
        AppQueryRequest request = new AppQueryRequest();
        request.setIsDelete(1);
        doAnswer(invocation -> {
            assertNull(LogicDeleteManager.getLogicDeleteColumn("isDelete"));
            throw new IllegalStateException("模拟查询失败");
        }).when(appService).page(ArgumentMatchers.<Page<App>>any(), any(QueryWrapper.class));

        assertThrows(IllegalStateException.class, () -> appService.listAppByPage(request));
        assertEquals("isDelete", LogicDeleteManager.getLogicDeleteColumn("isDelete"));
    }

    @Test
    void rejectInvalidPaginationBeforeDatabaseAccess() {
        AppQueryRequest request = new AppQueryRequest();
        request.setPageNum(0);
        assertThrows(BusinessException.class, () -> appService.listAppByPage(request));
        request.setPageNum(1);
        request.setPageSize(0);
        assertThrows(BusinessException.class, () -> appService.listAppByPage(request));
        verify(appService, never()).page(ArgumentMatchers.<Page<App>>any(), any(QueryWrapper.class));
    }

    @Test
    void deployAppTriggersScreenshotAndPersistsCover() throws IOException {
        long appId = 900_000L + Math.floorMod(System.nanoTime(), 100_000L);
        String deployKey = "shot" + RandomUtil.randomString(6);
        String deployUrl = "http://localhost:8123/" + deployKey + "/";
        String coverUrl = "https://cos.example.com/screenshots/cover.jpg";
        App app = App.builder()
                .id(appId)
                .userId(7L)
                .codeGenType(CodeGenTypeEnum.HTML.getValue())
                .deployKey(deployKey)
                .build();
        User loginUser = new User();
        loginUser.setId(7L);
        doReturn(app).when(appService).getById(appId);
        doReturn(true).when(appService).updateById(any(App.class));
        ReflectionTestUtils.setField(appService, "deployHost", "http://localhost:8123");
        when(screenshotService.generateAndUploadScreenshot(deployUrl)).thenReturn(coverUrl);

        File sourceDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, "html_" + appId);
        File deployDir = new File(AppConstant.CODE_DEPLOY_ROOT_DIR, deployKey);
        try {
            FileUtil.mkdir(sourceDir);
            FileUtil.writeUtf8String("<html><body>deployed</body></html>",
                    new File(sourceDir, "index.html"));

            assertEquals(deployUrl, appService.deployApp(appId, loginUser));

            // 截图在虚拟线程中执行，等待异步任务完成后再校验更新内容。
            verify(screenshotService, timeout(5_000)).generateAndUploadScreenshot(deployUrl);
            ArgumentCaptor<App> updateCaptor = ArgumentCaptor.forClass(App.class);
            verify(appService, timeout(5_000).times(2)).updateById(updateCaptor.capture());
            assertTrue(updateCaptor.getAllValues().stream()
                            .anyMatch(update -> coverUrl.equals(update.getCover())),
                    "部署后的异步任务应把截图地址写入应用封面");
        } finally {
            FileUtil.del(sourceDir);
            FileUtil.del(deployDir);
        }
    }

    @Test
    void deployAppCopiesBuiltDistForVueProject() throws IOException {
        long appId = 910_000L + Math.floorMod(System.nanoTime(), 100_000L);
        String deployKey = "vue" + RandomUtil.randomString(6);
        String deployUrl = "http://localhost:8123/" + deployKey + "/";
        App app = App.builder()
                .id(appId)
                .userId(7L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey(deployKey)
                .build();
        User loginUser = new User();
        loginUser.setId(7L);
        doReturn(app).when(appService).getById(appId);
        doReturn(true).when(appService).updateById(any(App.class));
        ReflectionTestUtils.setField(appService, "deployHost", "http://localhost:8123");

        File projectDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        File deployDir = new File(AppConstant.CODE_DEPLOY_ROOT_DIR, deployKey);
        try {
            FileUtil.mkdir(new File(projectDir, "dist/assets"));
            FileUtil.mkdir(new File(projectDir, "src"));
            FileUtil.writeUtf8String("<html><body>dev entry /src/main.js</body></html>",
                    new File(projectDir, "index.html"));
            FileUtil.writeUtf8String("<html><body>built app</body></html>",
                    new File(projectDir, "dist/index.html"));
            FileUtil.writeUtf8String("console.log('built')",
                    new File(projectDir, "dist/assets/app.js"));
            FileUtil.writeUtf8String("console.log('source')",
                    new File(projectDir, "src/main.js"));

            assertEquals(deployUrl, appService.deployApp(appId, loginUser));

            String deployedIndex = FileUtil.readUtf8String(new File(deployDir, "index.html"));
            assertTrue(deployedIndex.contains("built app"));
            assertFalse(deployedIndex.contains("/src/main.js"));
            assertTrue(FileUtil.exist(new File(deployDir, "assets/app.js")));
            assertFalse(FileUtil.exist(new File(deployDir, "src")),
                    "Vue 工程只应部署 dist，不应把源码目录暴露到部署目录");
        } finally {
            FileUtil.del(projectDir);
            FileUtil.del(deployDir);
        }
    }

    @Test
    void deployAppRejectsVueProjectWithoutBuildOutput() throws IOException {
        long appId = 920_000L + Math.floorMod(System.nanoTime(), 100_000L);
        String deployKey = "vue" + RandomUtil.randomString(6);
        App app = App.builder()
                .id(appId)
                .userId(7L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .deployKey(deployKey)
                .build();
        User loginUser = new User();
        loginUser.setId(7L);
        doReturn(app).when(appService).getById(appId);
        ReflectionTestUtils.setField(appService, "deployHost", "http://localhost:8123");

        File projectDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId);
        File deployDir = new File(AppConstant.CODE_DEPLOY_ROOT_DIR, deployKey);
        try {
            FileUtil.mkdir(new File(projectDir, "src"));
            FileUtil.writeUtf8String("<html><body>dev entry</body></html>",
                    new File(projectDir, "index.html"));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> appService.deployApp(appId, loginUser));
            assertTrue(exception.getMessage().contains("尚未构建完成"));
            assertFalse(FileUtil.exist(deployDir), "构建产物缺失时不应生成部署目录");
        } finally {
            FileUtil.del(projectDir);
            FileUtil.del(deployDir);
        }
    }

    @Test
    void generateAppScreenshotUpdatesCoverOnSuccess() {
        long appId = 999L;
        String coverUrl = "https://cos.example.com/screenshots/cover.jpg";
        when(screenshotService.generateAndUploadScreenshot("http://localhost/app/"))
                .thenReturn(coverUrl);
        doReturn(true).when(appService).updateById(any(App.class));

        appService.generateAppScreenshot(appId, "http://localhost/app/");

        ArgumentCaptor<App> updateCaptor = ArgumentCaptor.forClass(App.class);
        verify(appService).updateById(updateCaptor.capture());
        assertEquals(appId, updateCaptor.getValue().getId());
        assertEquals(coverUrl, updateCaptor.getValue().getCover());
    }

    @Test
    void screenshotFailureDoesNotUpdateCoverOrThrow() {
        when(screenshotService.generateAndUploadScreenshot("http://localhost/app/"))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "截图失败"));

        assertDoesNotThrow(() -> appService.generateAppScreenshot(1L, "http://localhost/app/"));

        verify(appService, never()).updateById(any(App.class));
    }

    @Test
    void blankScreenshotResultDoesNotUpdateCover() {
        when(screenshotService.generateAndUploadScreenshot("http://localhost/app/"))
                .thenReturn(" ");

        assertDoesNotThrow(() -> appService.generateAppScreenshot(1L, "http://localhost/app/"));

        verify(appService, never()).updateById(any(App.class));
    }

    private String toSql(QueryWrapper queryWrapper) {
        return queryWrapper.from("app").toSQL(DbType.MYSQL).replace("`", "");
    }
}
