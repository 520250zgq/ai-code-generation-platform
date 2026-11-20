package com.zgq.zgqaicodemother.security.ratelimit;

import com.zgq.zgqaicodemother.controller.RateLimitTestController;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 限流切面测试：通过真实切入点验证无注解提前放行、用户/IP 维度组合、重复注解和异常提示。
 *
 * <p>测试使用 AspectJProxyFactory 和 MockHttpServletRequest，不启动 Spring 容器；
 * 限流器用可计数的 Mock 替代，从而把切面职责与 Redis 计数逻辑分开验证。</p>
 */
class RateLimitAspectTest {

    private static final String FIRST_IP = "203.0.113.7";

    private static final String SECOND_IP = "198.51.100.8";

    private MockHttpServletRequest request;

    private UserService userService;

    private RateLimiter rateLimiter;

    private RateLimitTestController controller;

    private Map<String, AtomicInteger> acquireCounts;

    private AtomicInteger acquireCalls;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.setRemoteAddr(FIRST_IP);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        userService = mock(UserService.class);
        rateLimiter = mock(RateLimiter.class);
        acquireCounts = new ConcurrentHashMap<>();
        acquireCalls = new AtomicInteger();
        when(rateLimiter.acquire(anyString(), anyInt(), anyLong())).thenAnswer(invocation -> {
            acquireCalls.incrementAndGet();
            String key = invocation.getArgument(0);
            Integer permits = invocation.getArgument(1);
            Long windowSeconds = invocation.getArgument(2);
            int used = acquireCounts.computeIfAbsent(key, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (used <= permits) {
                return RateLimitDecision.allowed(permits - used);
            }
            return RateLimitDecision.rejected(windowSeconds);
        });

        RateLimitProperties properties = new RateLimitProperties();
        RateLimitAspect aspect = new RateLimitAspect(properties, rateLimiter, userService);
        AspectJProxyFactory factory = new AspectJProxyFactory(new RateLimitTestController());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        controller = factory.getProxy();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void methodWithoutAnnotationProceedsWithoutLimiterOrLoginLookup() {
        assertEquals("ok", controller.noAnnotation());

        assertEquals(0, acquireCalls.get());
        verifyNoInteractions(userService);
    }

    @Test
    void secondRequestIsRejectedBySingleRuleWithDefaultMessage() {
        assertEquals("ok", controller.singleDimension());

        RateLimitExceededException error = assertThrows(RateLimitExceededException.class,
                controller::singleDimension);

        assertEquals("aspect-single", error.getRule());
        // 未登录请求没有用户维度，只能按 IP 维度拦截。
        assertEquals("ip", error.getDimension());
        assertEquals("请求过于频繁，请稍后重试", error.getMessage());
        assertTrue(error.getRetryAfterSeconds() >= 1L);
    }

    @Test
    void authenticatedUserIsStillRejectedAfterChangingIp() {
        loginAs(7L);
        assertEquals("ok", controller.userOnly());

        request.setRemoteAddr(SECOND_IP);
        RateLimitExceededException error = assertThrows(RateLimitExceededException.class,
                controller::userOnly);

        assertEquals("aspect-user", error.getRule());
        assertEquals("user", error.getDimension());
    }

    @Test
    void byUserFalseLimitsOnlyByIpWithoutLoginLookup() {
        assertEquals("ok", controller.ipOnly());

        request.setRemoteAddr(SECOND_IP);
        assertEquals("ok", controller.ipOnly());

        request.setRemoteAddr(FIRST_IP);
        assertThrows(RateLimitExceededException.class, controller::ipOnly);

        // 该规则显式关闭用户维度后，不应为了限流而查询 Session/数据库登录态。
        verifyNoInteractions(userService);
    }

    @Test
    void repeatedAnnotationsApplyEveryRule() {
        loginAs(7L);

        assertEquals("ok", controller.repeatedRules());

        // 两条重复规则各生成用户和 IP 两个维度，第一次调用共四次计数。
        verify(rateLimiter).acquire(eq("aspect-repeat-first:user:7"), eq(10), eq(60L));
        verify(rateLimiter).acquire(eq("aspect-repeat-first:ip:" + FIRST_IP), eq(10), eq(60L));
        verify(rateLimiter).acquire(eq("aspect-repeat-second:user:7"), eq(1), eq(60L));
        verify(rateLimiter).acquire(eq("aspect-repeat-second:ip:" + FIRST_IP), eq(1), eq(60L));

        RateLimitExceededException error = assertThrows(RateLimitExceededException.class,
                controller::repeatedRules);

        assertEquals("aspect-repeat-second", error.getRule());
        assertEquals("第二个规则触发", error.getMessage());
    }

    @Test
    void businessExceptionDuringLoginFallsBackToIpDimension() {
        when(userService.getLoginUser(request))
                .thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));

        assertEquals("ok", controller.singleDimension());
        RateLimitExceededException error = assertThrows(RateLimitExceededException.class,
                controller::singleDimension);

        assertEquals("ip", error.getDimension());
    }

    private void loginAs(long userId) {
        User loginUser = new User();
        loginUser.setId(userId);
        when(userService.getLoginUser(request)).thenReturn(loginUser);
    }
}
