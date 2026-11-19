package com.zgq.zgqaicodemother.controller;

import cn.hutool.json.JSONUtil;
import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.exception.BusinessException;
import com.zgq.zgqaicodemother.exception.ErrorCode;
import com.zgq.zgqaicodemother.model.entity.User;
import com.zgq.zgqaicodemother.service.UserService;
import com.zgq.zgqaicodemother.workflow.model.WorkflowEvent;
import com.zgq.zgqaicodemother.workflow.model.WorkflowResult;
import com.zgq.zgqaicodemother.workflow.model.dto.WorkflowExecuteRequest;
import com.zgq.zgqaicodemother.workflow.service.WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 工作流控制器测试：同步结果、SSE 事件映射和错误事件。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowControllerTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private UserService userService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private WorkflowController controller;

    private final User loginUser = loginUser();

    @Test
    void syncExecuteReturnsWorkflowResult() {
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        WorkflowResult expected = result(true);
        when(workflowService.execute(anyLong(), any(), any())).thenReturn(expected);
        WorkflowExecuteRequest executeRequest = new WorkflowExecuteRequest();
        executeRequest.setAppId(1L);
        executeRequest.setPrompt("做一个官网");

        BaseResponse<WorkflowResult> response = controller.execute(executeRequest, request);

        assertEquals(0, response.getCode());
        assertEquals(expected, response.getData());
    }

    @Test
    void streamMapsEventsToSse() {
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        WorkflowResult result = result(true);
        when(workflowService.executeStream(anyLong(), any(), any())).thenReturn(Flux.just(
                WorkflowEvent.start("wf-1", "开始"),
                WorkflowEvent.nodeCompleted("wf-1", "image_collector", "图片收集", 1, "收集到 1 张图片", 10),
                WorkflowEvent.completed("wf-1", "完成", 20, result)));

        List<ServerSentEvent<String>> events = controller.executeStream(1L, null, request)
                .collectList().block();

        assertEquals(3, events.size());
        assertEquals("workflow_start", events.get(0).event());
        assertEquals("node_completed", events.get(1).event());
        assertEquals("workflow_completed", events.get(2).event());
        Map<?, ?> data = JSONUtil.parseObj(events.get(1).data());
        assertEquals("image_collector", data.get("node"));
        assertEquals(1, data.get("step"));
        assertTrue(events.get(2).data().contains("\"success\":true"));
    }

    @Test
    void streamErrorBecomesWorkflowErrorEvent() {
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(workflowService.executeStream(anyLong(), any(), any()))
                .thenReturn(Flux.error(new BusinessException(ErrorCode.OPERATION_ERROR, "模型不可用")));

        List<ServerSentEvent<String>> events = controller.executeStream(1L, null, request)
                .collectList().block();

        assertEquals(1, events.size());
        assertEquals("workflow_error", events.get(0).event());
        assertTrue(events.get(0).data().contains("模型不可用"));
    }

    @Test
    void rejectsInvalidAppIdBeforeLogin() {
        WorkflowExecuteRequest executeRequest = new WorkflowExecuteRequest();
        executeRequest.setAppId(0L);

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.execute(executeRequest, request));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), error.getCode());
    }

    @Test
    void graphAndStatusRequireLogin() {
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(workflowService.mermaidGraph()).thenReturn("flowchart LR\n A-->B");
        when(workflowService.status()).thenReturn(Map.of("enabled", true));

        assertEquals("flowchart LR\n A-->B", controller.graph(request).getData());
        assertEquals(true, controller.status(request).getData().get("enabled"));
    }

    private static WorkflowResult result(boolean success) {
        return new WorkflowResult("wf-1", 1L, success, "完成", "html",
                "html_1", "html_1", 1, 20,
                List.of(), List.of(), null, 30L);
    }

    private static User loginUser() {
        User user = new User();
        user.setId(1L);
        return user;
    }
}
