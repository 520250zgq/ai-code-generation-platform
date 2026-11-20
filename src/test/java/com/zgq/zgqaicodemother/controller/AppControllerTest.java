package com.zgq.zgqaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.zgq.zgqaicodemother.aop.AuthInterceptor;
import com.zgq.zgqaicodemother.common.DeleteRequest;
import com.zgq.zgqaicodemother.constant.UserConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.dto.app.AppAddRequest;
import com.zgq.zgqaicodemother.model.dto.app.AppAdminUpdateRequest;
import com.zgq.zgqaicodemother.model.dto.app.AppQueryRequest;
import com.zgq.zgqaicodemother.model.dto.app.AppUpdateRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.model.vo.AppVO;
import com.zgq.zgqaicodemother.preview.PreviewService;
import com.zgq.zgqaicodemother.preview.PreviewState;
import com.zgq.zgqaicodemother.preview.PreviewStatus;
import com.zgq.zgqaicodemother.security.prompt.PromptReviewProperties;
import com.zgq.zgqaicodemother.security.prompt.PromptReviewService;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Application authorization and request-boundary regressions without a database or Spring context.
 */
@ExtendWith(MockitoExtension.class)
class AppControllerTest {

    private static final long APP_ID = 101L;
    private static final long OWNER_ID = 7L;

    @Mock
    private AppService appService;

    @Mock
    private UserService userService;

    @Mock
    private PreviewService previewService;

    @InjectMocks
    private AppController controller;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private User loginUser;

    @BeforeEach
    void setUp() {
        loginUser = new User();
        loginUser.setId(OWNER_ID);
        loginUser.setUserRole(UserConstant.DEFAULT_ROLE);
        // 审查器使用真实实现：入口安全检查是本次优化的核心行为之一，mock 掉就覆盖不到了。
        ReflectionTestUtils.setField(controller, "promptReviewService",
                new PromptReviewService(new PromptReviewProperties()));
        // Some invalid requests are rejected before login lookup, others after it.
        lenient().when(userService.getLoginUser(request)).thenReturn(loginUser);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void creationDelegatesAuthenticatedUserToService() {
        AppAddRequest input = new AppAddRequest();
        input.setInitPrompt("创建个人作品集");
        when(appService.addApp(input, loginUser)).thenReturn(APP_ID);

        assertEquals(APP_ID, controller.addApp(input, request).getData());

        verify(userService).getLoginUser(request);
        verify(appService).addApp(input, loginUser);
        verify(appService, never()).save(any(App.class));
    }

    @Test
    void blankCreationPromptIsRejectedBeforeServiceAccess() {
        AppAddRequest input = new AppAddRequest();
        input.setInitPrompt(" \n\t ");

        assertError(ErrorCode.PARAMS_ERROR, () -> controller.addApp(input, request));

        verifyNoInteractions(appService);
    }

    @ParameterizedTest
    @ValueSource(strings = {UserConstant.DEFAULT_ROLE, UserConstant.ADMIN_ROLE})
    void userUpdateRejectsAnotherOwnersAppEvenForAnAdmin(String role) {
        loginUser.setUserRole(role);
        when(appService.getById(APP_ID)).thenReturn(appOwnedBy(OWNER_ID + 1));

        assertError(ErrorCode.NO_AUTH_ERROR, () -> controller.updateApp(updateRequest(), request));

        verify(appService, never()).updateById(any(App.class));
    }

    @Test
    void ownerUpdateChangesOnlyNameAndEditTime() {
        App existing = appOwnedBy(OWNER_ID);
        existing.setCover("https://example.com/cover.png");
        existing.setInitPrompt("original prompt");
        existing.setPriority(99);
        when(appService.getById(APP_ID)).thenReturn(existing);
        when(appService.updateById(any(App.class))).thenReturn(true);
        LocalDateTime before = LocalDateTime.now();

        assertTrue(controller.updateApp(updateRequest(), request).getData());

        ArgumentCaptor<App> updated = ArgumentCaptor.forClass(App.class);
        verify(appService).updateById(updated.capture());
        App patch = updated.getValue();
        assertEquals(APP_ID, patch.getId());
        assertEquals("更新后的应用", patch.getAppName());
        assertNotNull(patch.getEditTime());
        assertTrue(!patch.getEditTime().isBefore(before));
        assertTrue(!patch.getEditTime().isAfter(LocalDateTime.now()));
        assertNull(patch.getCover());
        assertNull(patch.getInitPrompt());
        assertNull(patch.getPriority());
        assertNull(patch.getUserId());
        assertNull(patch.getIsDelete());
        assertEquals("https://example.com/cover.png", existing.getCover());
    }

    @ParameterizedTest
    @CsvSource({"user,7", "admin,8"})
    void ownerOrAdminCanDelete(String role, long appOwner) {
        loginUser.setUserRole(role);
        when(appService.getById(APP_ID)).thenReturn(appOwnedBy(appOwner));
        when(appService.removeAppWithChatHistory(APP_ID)).thenReturn(true);

        assertTrue(controller.deleteApp(deleteRequest(), request).getData());

        verify(appService).removeAppWithChatHistory(APP_ID);
    }

    @Test
    void strangerCannotDelete() {
        when(appService.getById(APP_ID)).thenReturn(appOwnedBy(OWNER_ID + 1));

        assertError(ErrorCode.NO_AUTH_ERROR, () -> controller.deleteApp(deleteRequest(), request));

        verify(appService, never()).removeAppWithChatHistory(APP_ID);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidMutationIdsAreRejectedBeforePersistence(Long id) {
        AppUpdateRequest update = updateRequest();
        update.setId(id);
        DeleteRequest deletion = deleteRequest();
        deletion.setId(id);
        AppAdminUpdateRequest adminUpdate = adminUpdateRequest();
        adminUpdate.setId(id);

        assertError(ErrorCode.PARAMS_ERROR, () -> controller.updateApp(update, request));
        assertError(ErrorCode.PARAMS_ERROR, () -> controller.deleteApp(deletion, request));
        assertError(ErrorCode.PARAMS_ERROR, () -> controller.adminUpdateApp(adminUpdate));

        verifyNoInteractions(appService);
    }

    @Test
    void authenticatedUserCanReadAnotherApplicationsView() {
        App existing = appOwnedBy(OWNER_ID + 1);
        AppVO view = new AppVO();
        when(appService.getById(APP_ID)).thenReturn(existing);
        when(appService.getAppVO(existing)).thenReturn(view);

        assertSame(view, controller.getAppVOById(APP_ID, request).getData());

        verify(userService).getLoginUser(request);
    }

    @Test
    void ownerDownloadDelegatesWithAuthenticatedUser() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.downloadAppCode(APP_ID, request, response);

        verify(userService).getLoginUser(request);
        verify(appService).downloadAppCode(APP_ID, loginUser, response);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void downloadRejectsInvalidIdsBeforeServiceAccess(Long id) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertError(ErrorCode.PARAMS_ERROR, () -> controller.downloadAppCode(id, request, response));

        verifyNoInteractions(appService);
    }

    @Test
    void unauthenticatedDownloadIsRejected() {
        when(userService.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertError(ErrorCode.NOT_LOGIN_ERROR, () -> controller.downloadAppCode(APP_ID, request, response));

        verifyNoInteractions(appService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "update", "delete"})
    void missingApplicationReturnsNotFound(String operation) {
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> {
            switch (operation) {
                case "read" -> controller.getAppVOById(APP_ID, request);
                case "update" -> controller.updateApp(updateRequest(), request);
                case "delete" -> controller.deleteApp(deleteRequest(), request);
                default -> throw new AssertionError(operation);
            }
        });

        verify(appService, never()).updateById(any(App.class));
        verify(appService, never()).removeAppWithChatHistory(APP_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "my", "good"})
    void unauthenticatedUserCannotReadDetailsOrLists(String operation) {
        when(userService.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertError(ErrorCode.NOT_LOGIN_ERROR, () -> {
            switch (operation) {
                case "read" -> controller.getAppVOById(APP_ID, request);
                case "my" -> controller.listMyAppVOByPage(new AppQueryRequest(), request);
                case "good" -> controller.listGoodAppVOByPage(new AppQueryRequest(), request);
                default -> throw new AssertionError(operation);
            }
        });

        verifyNoInteractions(appService);
    }

    @ParameterizedTest
    @CsvSource({"0,10", "-1,10", "1,0", "1,-1", "1,21"})
    void userListsRejectInvalidPageBounds(int pageNum, int pageSize) {
        AppQueryRequest input = maliciousQuery();
        input.setPageNum(pageNum);
        input.setPageSize(pageSize);

        assertError(ErrorCode.PARAMS_ERROR, () -> controller.listMyAppVOByPage(input, request));
        assertError(ErrorCode.PARAMS_ERROR, () -> controller.listGoodAppVOByPage(input, request));

        verify(appService, never()).listAppByPage(any(AppQueryRequest.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void userListsIgnorePrivilegedFiltersAndDoNotMutateInput(boolean good) {
        AppQueryRequest input = maliciousQuery();
        Page<App> records = Page.of(2, 20);
        Page<AppVO> views = Page.of(2, 20);
        // 两个列表入口共用同一组断言，按参数只走其中一条路径，故用 lenient 声明两条链路的桩。
        lenient().when(appService.listAppByPage(any(AppQueryRequest.class))).thenReturn(records);
        lenient().when(appService.getAppVOPage(records)).thenReturn(views);
        lenient().when(appService.listGoodAppVOPage(any(AppQueryRequest.class))).thenReturn(views);

        Page<AppVO> actual = good
                ? controller.listGoodAppVOByPage(input, request).getData()
                : controller.listMyAppVOByPage(input, request).getData();

        assertSame(views, actual);
        ArgumentCaptor<AppQueryRequest> captured = ArgumentCaptor.forClass(AppQueryRequest.class);
        if (good) {
            // 精选列表走带缓存的入口，查询条件仍必须只保留普通用户可用字段。
            verify(appService).listGoodAppVOPage(captured.capture());
        } else {
            verify(appService).listAppByPage(captured.capture());
        }
        AppQueryRequest safe = captured.getValue();
        assertNotSame(input, safe);
        assertEquals(2, safe.getPageNum());
        assertEquals(20, safe.getPageSize());
        assertEquals("作品", safe.getAppName());
        assertEquals("createTime", safe.getSortField());
        assertEquals("ascend", safe.getSortOrder());
        assertEquals(good ? null : OWNER_ID, safe.getUserId());
        assertEquals(good ? 99 : null, safe.getPriority());
        assertNull(safe.getId());
        assertNull(safe.getIsDelete());
        assertNull(safe.getInitPrompt());
        assertNull(safe.getCover());
        assertNull(safe.getDeployKey());
        assertNull(safe.getCodeGenType());
        assertEquals(999L, input.getUserId());
        assertEquals(1, input.getPriority());
        assertEquals(1, input.getIsDelete());
    }

    @ParameterizedTest
    @ValueSource(strings = {"get", "list", "update"})
    void adminEndpointsRejectOrdinaryUsersThroughTheRealAuthAspect(String operation) {
        AppController protectedController = protectedController();

        assertError(ErrorCode.NO_AUTH_ERROR, () -> invokeAdminEndpoint(protectedController, operation));

        verifyNoInteractions(appService);
    }

    @Test
    void adminCanQueryMoreThanTwentyRecordsThroughTheRealAuthAspect() {
        loginUser.setUserRole(UserConstant.ADMIN_ROLE);
        AppQueryRequest input = maliciousQuery();
        input.setPageSize(100);
        Page<App> records = Page.of(2, 100);
        when(appService.listAppByPage(input)).thenReturn(records);

        assertSame(records, protectedController().listAppByPage(input).getData());

        verify(appService).listAppByPage(input);
    }

    @Test
    void adminCanReadTheRawApplicationThroughTheRealAuthAspect() {
        loginUser.setUserRole(UserConstant.ADMIN_ROLE);
        App existing = appOwnedBy(OWNER_ID + 1);
        when(appService.getById(APP_ID)).thenReturn(existing);

        assertSame(existing, protectedController().getAppById(APP_ID).getData());
    }

    @Test
    void adminCanUpdateAnotherOwnersNameCoverAndPriorityThroughTheRealAuthAspect() {
        loginUser.setUserRole(UserConstant.ADMIN_ROLE);
        AppAdminUpdateRequest input = adminUpdateRequest();
        when(appService.getById(APP_ID)).thenReturn(appOwnedBy(OWNER_ID + 1));
        when(appService.updateById(any(App.class))).thenReturn(true);

        assertTrue(protectedController().adminUpdateApp(input).getData());

        ArgumentCaptor<App> updated = ArgumentCaptor.forClass(App.class);
        verify(appService).updateById(updated.capture());
        App patch = updated.getValue();
        assertEquals(APP_ID, patch.getId());
        assertEquals(input.getAppName(), patch.getAppName());
        assertEquals(input.getCover(), patch.getCover());
        assertEquals(input.getPriority(), patch.getPriority());
        assertNotNull(patch.getEditTime());
        assertNull(patch.getUserId());
        assertNull(patch.getInitPrompt());
        assertNull(patch.getIsDelete());
    }

    @Test
    void emptyAdminUpdateCannotWriteAnEditTimestampAlone() {
        AppAdminUpdateRequest input = new AppAdminUpdateRequest();
        input.setId(APP_ID);

        assertError(ErrorCode.PARAMS_ERROR, () -> controller.adminUpdateApp(input));

        verify(appService, never()).updateById(any(App.class));
    }

    @Test
    void previewStatusReturnsLatestSnapshotForExistingApp() {
        App existing = appOwnedBy(OWNER_ID);
        existing.setCodeGenType("vue_project");
        when(appService.getById(APP_ID)).thenReturn(existing);
        PreviewStatus snapshot = new PreviewStatus(APP_ID, "vue_project", PreviewState.BUILDING,
                3L, false, "static/vue_project_" + APP_ID + "/", "正在构建", 1_700_000_000_000L);
        when(previewService.snapshot(APP_ID, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(snapshot);

        assertSame(snapshot, controller.getPreviewStatus(APP_ID, request).getData());

        verify(userService).getLoginUser(request);
    }

    @Test
    void previewStatusRejectsMissingApp() {
        assertError(ErrorCode.NOT_FOUND_ERROR, () -> controller.getPreviewStatus(APP_ID, request));

        verifyNoInteractions(previewService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void previewStatusRejectsInvalidIds(Long id) {
        assertError(ErrorCode.PARAMS_ERROR, () -> controller.getPreviewStatus(id, request));

        verifyNoInteractions(appService, previewService);
    }

    @Test
    void previewStatusRequiresLogin() {
        when(userService.getLoginUser(request))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertError(ErrorCode.NOT_LOGIN_ERROR, () -> controller.getPreviewStatus(APP_ID, request));

        verifyNoInteractions(previewService);
    }

    private AppController protectedController() {
        AuthInterceptor aspect = new AuthInterceptor();
        ReflectionTestUtils.setField(aspect, "userService", userService);
        AspectJProxyFactory factory = new AspectJProxyFactory(controller);
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return factory.getProxy();
    }

    private void invokeAdminEndpoint(AppController target, String operation) {
        switch (operation) {
            case "get" -> target.getAppById(APP_ID);
            case "list" -> target.listAppByPage(new AppQueryRequest());
            case "update" -> target.adminUpdateApp(adminUpdateRequest());
            default -> throw new AssertionError(operation);
        }
    }

    private static App appOwnedBy(long userId) {
        App app = new App();
        app.setId(APP_ID);
        app.setUserId(userId);
        app.setAppName("原应用");
        return app;
    }

    private static AppUpdateRequest updateRequest() {
        AppUpdateRequest input = new AppUpdateRequest();
        input.setId(APP_ID);
        input.setAppName("更新后的应用");
        return input;
    }

    private static AppAdminUpdateRequest adminUpdateRequest() {
        AppAdminUpdateRequest input = new AppAdminUpdateRequest();
        input.setId(APP_ID);
        input.setAppName("精选作品");
        input.setCover("https://example.com/new-cover.png");
        input.setPriority(99);
        return input;
    }

    private static DeleteRequest deleteRequest() {
        DeleteRequest input = new DeleteRequest();
        input.setId(APP_ID);
        return input;
    }

    private static AppQueryRequest maliciousQuery() {
        AppQueryRequest input = new AppQueryRequest();
        input.setPageNum(2);
        input.setPageSize(20);
        input.setAppName("作品");
        input.setSortField("createTime");
        input.setSortOrder("ascend");
        input.setUserId(999L);
        input.setPriority(1);
        input.setIsDelete(1);
        input.setId(999L);
        input.setInitPrompt("private prompt");
        input.setCover("private cover");
        input.setCodeGenType("html");
        input.setDeployKey("private key");
        return input;
    }

    private static void assertError(ErrorCode code, Executable action) {
        BusinessException error = assertThrows(BusinessException.class, action);
        assertEquals(code.getCode(), error.getCode());
    }
}
