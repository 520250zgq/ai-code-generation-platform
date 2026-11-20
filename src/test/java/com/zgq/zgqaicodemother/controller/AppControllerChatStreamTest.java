package com.zgq.zgqaicodemother.controller;

import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.security.prompt.PromptReviewProperties;
import com.zgq.zgqaicodemother.security.prompt.PromptReviewService;
import com.zgq.zgqaicodemother.service.AppService;
import com.zgq.zgqaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 验证 SSE 成功和失败时的事件格式：失败必须发送 error 事件，而不是直接断开连接。
 */
@ExtendWith(MockitoExtension.class)
class AppControllerChatStreamTest {

    private static final long APP_ID = 101L;
    private static final String MESSAGE = "把页面调好看一点";

    @Mock
    private AppService appService;

    @Mock
    private UserService userService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private AppController controller;

    private final User loginUser = loginUser();

    /**
     * 使用真实审查器：正常需求必须能原样通过，否则这里会误报为参数错误。
     */
    @BeforeEach
    void setUpPromptReview() {
        ReflectionTestUtils.setField(controller, "promptReviewService",
                new PromptReviewService(new PromptReviewProperties()));
    }

    @Test
    void successfulStreamEndsWithDoneEvent() {
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(appService.chatToGenCode(APP_ID, MESSAGE, loginUser))
                .thenReturn(Flux.just("part-1", "part-2"));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(APP_ID, MESSAGE, request)
                .collectList().block();

        assertEquals(3, events.size());
        assertEquals("{\"d\":\"part-1\"}", events.get(0).data());
        assertEquals("{\"d\":\"part-2\"}", events.get(1).data());
        assertEquals("done", events.get(2).event());
    }

    @Test
    void failedStreamEmitsErrorEventInsteadOfDroppingConnection() {
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(appService.chatToGenCode(APP_ID, MESSAGE, loginUser))
                .thenReturn(Flux.concat(Flux.just("partial"),
                        Flux.error(new BusinessException(ErrorCode.SYSTEM_ERROR,
                                "AI 返回的 JSON 代码不完整，请重试"))));

        List<ServerSentEvent<String>> events = controller.chatToGenCode(APP_ID, MESSAGE, request)
                .collectList().block();

        assertEquals(2, events.size());
        assertEquals("error", events.get(1).event());
        assertTrue(events.get(1).data().contains("不完整"));
    }

    private static User loginUser() {
        User user = new User();
        user.setId(1L);
        return user;
    }
}
