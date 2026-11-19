package com.zgq.zgqaicodemother.controller;

import com.mybatisflex.core.paginate.Page;
import com.zgq.annotation.AuthCheck;
import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.constant.UserConstant;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryAdminQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorPage;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryCursorQueryRequest;
import com.zgq.zgqaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import com.zgq.zgqaicodemother.model.entity.App;
import com.zgq.zgqaicodemother.model.entity.ChatHistory;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.service.ChatHistoryService;
import com.zgq.zgqaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryControllerTest {

    private static final long APP_ID = 17L;
    private static final long OWNER_ID = 8L;

    @Mock
    private ChatHistoryService chatHistoryService;

    @Mock
    private AppService appService;

    @Mock
    private UserService userService;

    @InjectMocks
    private ChatHistoryController controller;

    private final HttpServletRequest request = new MockHttpServletRequest();

    @Test
    void appOwnerCanLoadTenMessagePage() {
        User owner = user(OWNER_ID, UserConstant.DEFAULT_ROLE);
        App app = app(OWNER_ID);
        Page<ChatHistory> page = new Page<>(1, 10);
        when(userService.getLoginUser(request)).thenReturn(owner);
        when(appService.getById(APP_ID)).thenReturn(app);
        when(chatHistoryService.listByAppId(APP_ID, 1)).thenReturn(page);
        ChatHistoryQueryRequest query = new ChatHistoryQueryRequest();
        query.setAppId(APP_ID);

        BaseResponse<Page<ChatHistory>> response = controller.listAppHistoryByPage(query, request);

        assertSame(page, response.getData());
        verify(chatHistoryService).listByAppId(APP_ID, 1);
    }

    @Test
    void anotherUserCannotLoadPrivateApplicationHistory() {
        User stranger = user(OWNER_ID + 1, UserConstant.DEFAULT_ROLE);
        when(userService.getLoginUser(request)).thenReturn(stranger);
        when(appService.getById(APP_ID)).thenReturn(app(OWNER_ID));
        ChatHistoryQueryRequest query = new ChatHistoryQueryRequest();
        query.setAppId(APP_ID);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.listAppHistoryByPage(query, request));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(chatHistoryService, never()).listByAppId(APP_ID, 1);
    }

    @Test
    void appOwnerCanLoadCursorHistory() {
        User owner = user(OWNER_ID, UserConstant.DEFAULT_ROLE);
        App app = app(OWNER_ID);
        ChatHistoryCursorPage page = new ChatHistoryCursorPage();
        when(userService.getLoginUser(request)).thenReturn(owner);
        when(appService.getById(APP_ID)).thenReturn(app);
        when(chatHistoryService.listByAppIdCursor(APP_ID, null, null, 10)).thenReturn(page);
        ChatHistoryCursorQueryRequest query = new ChatHistoryCursorQueryRequest();
        query.setAppId(APP_ID);

        BaseResponse<ChatHistoryCursorPage> response = controller.listAppHistoryByCursor(query, request);

        assertSame(page, response.getData());
        verify(chatHistoryService).listByAppIdCursor(APP_ID, null, null, 10);
    }

    @Test
    void rejectIncompleteCursorBeforeLoadingApplication() {
        ChatHistoryCursorQueryRequest query = new ChatHistoryCursorQueryRequest();
        query.setAppId(APP_ID);
        query.setCursorId("123");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.listAppHistoryByCursor(query, request));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(userService, never()).getLoginUser(request);
        verify(appService, never()).getById(APP_ID);
    }

    @Test
    void globalHistoryQueryDelegatesAdminFilters() {
        ChatHistoryAdminQueryRequest query = new ChatHistoryAdminQueryRequest();
        query.setAppId(APP_ID);
        query.setUserId(OWNER_ID);
        query.setMessageType("ai");
        query.setPageNum(2);
        query.setPageSize(20);
        Page<ChatHistory> page = new Page<>(2, 20);
        when(chatHistoryService.listForAdmin(query)).thenReturn(page);

        BaseResponse<Page<ChatHistory>> response = controller.listAllHistoryByPage(query);

        assertSame(page, response.getData());
        verify(chatHistoryService).listForAdmin(query);
    }

    @Test
    void rejectUnknownMessageTypeBeforeAdminQuery() {
        ChatHistoryAdminQueryRequest query = new ChatHistoryAdminQueryRequest();
        query.setMessageType("system");

        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.listAllHistoryByPage(query));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(chatHistoryService, never()).listForAdmin(query);
    }

    @Test
    void globalHistoryQueryIsAdminOnly() throws NoSuchMethodException {
        AuthCheck authCheck = ChatHistoryController.class
                .getMethod("listAllHistoryByPage", ChatHistoryAdminQueryRequest.class)
                .getAnnotation(AuthCheck.class);

        assertEquals(UserConstant.ADMIN_ROLE, authCheck.mustRole());
    }

    private static App app(long ownerId) {
        App app = new App();
        app.setId(APP_ID);
        app.setUserId(ownerId);
        return app;
    }

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setUserRole(role);
        return user;
    }
}
