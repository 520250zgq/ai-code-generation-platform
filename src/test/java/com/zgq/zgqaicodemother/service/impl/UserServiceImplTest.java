package com.zgq.zgqaicodemother.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.mapper.UserMapper;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.model.vo.LoginUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.zgq.zgqaicodemother.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录态只保存 userId，并在登录/注销时正确轮换或删除 Session。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final long USER_ID = 42L;

    @Mock
    private UserMapper userMapper;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpSession oldSession;

    @Mock
    private HttpSession newSession;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "mapper", userMapper);
    }

    @Test
    void loginRotatesSessionAndStoresOnlyUserId() {
        User user = user(USER_ID);
        when(userMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(user);
        when(request.getSession(false)).thenReturn(oldSession);
        when(request.getSession(true)).thenReturn(newSession);

        LoginUserVO loginUser = userService.userLogin("tester", "test12345", request);

        assertEquals("tester", loginUser.getUserAccount());
        verify(oldSession).invalidate();
        verify(newSession).setAttribute(USER_LOGIN_STATE, USER_ID);
    }

    @Test
    void loginWithoutExistingSessionCreatesOne() {
        when(userMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(user(USER_ID));
        when(request.getSession(false)).thenReturn(null);
        when(request.getSession(true)).thenReturn(newSession);

        userService.userLogin("tester", "test12345", request);

        verify(newSession).setAttribute(USER_LOGIN_STATE, USER_ID);
        verify(oldSession, never()).invalidate();
    }

    @Test
    void getLoginUserLoadsCurrentUserFromDatabase() {
        User user = user(USER_ID);
        when(request.getSession(false)).thenReturn(newSession);
        when(newSession.getAttribute(USER_LOGIN_STATE)).thenReturn(USER_ID);
        when(userMapper.selectOneById(USER_ID)).thenReturn(user);

        assertSame(user, userService.getLoginUser(request));
    }

    @Test
    void getLoginUserSupportsLegacyUserObjectInSession() {
        User legacy = user(USER_ID);
        when(request.getSession(false)).thenReturn(newSession);
        when(newSession.getAttribute(USER_LOGIN_STATE)).thenReturn(legacy);
        when(userMapper.selectOneById(USER_ID)).thenReturn(legacy);

        assertSame(legacy, userService.getLoginUser(request));
    }

    @Test
    void getLoginUserRejectsMissingSessionOrUser() {
        when(request.getSession(false)).thenReturn(null);
        assertThrows(BusinessException.class, () -> userService.getLoginUser(request));

        when(request.getSession(false)).thenReturn(newSession);
        when(newSession.getAttribute(USER_LOGIN_STATE)).thenReturn(USER_ID);
        when(userMapper.selectOneById(USER_ID)).thenReturn(null);
        assertThrows(BusinessException.class, () -> userService.getLoginUser(request));
        verify(newSession).invalidate();
    }

    @Test
    void logoutInvalidatesWholeSession() {
        when(request.getSession(false)).thenReturn(newSession);
        when(newSession.getAttribute(USER_LOGIN_STATE)).thenReturn(USER_ID);

        assertTrue(userService.userLogout(request));
        verify(newSession).invalidate();
    }

    @Test
    void logoutWithoutLoginDoesNotCreateSession() {
        when(request.getSession(false)).thenReturn(null);

        assertThrows(BusinessException.class, () -> userService.userLogout(request));
        verify(request, never()).getSession(true);
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        user.setUserAccount("tester");
        user.setUserName("测试用户");
        user.setUserRole("user");
        return user;
    }
}
