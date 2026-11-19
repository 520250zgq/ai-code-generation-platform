package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.ai.AiCodeGenTypeRoutingService;
import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 智能路由节点测试。
 */
class RouterNodeTest {

    @Test
    void usesRoutingResult() {
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        when(routingService.routeCodeGenType("做一个复杂后台")).thenReturn(CodeGenTypeEnum.VUE_PROJECT);
        WorkflowContext context = WorkflowContext.builder()
                .originalPrompt("做一个复杂后台")
                .build();

        WorkflowContext updated = run(new RouterNode(routingService), context);

        assertEquals(CodeGenTypeEnum.VUE_PROJECT, updated.getGenerationType());
        assertTrue(updated.getCurrentStep().contains("Vue"));
        assertTrue(updated.getWarnings().isEmpty());
    }

    @Test
    void fallsBackToHtmlWhenRoutingFails() {
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        when(routingService.routeCodeGenType(anyString())).thenThrow(new RuntimeException("timeout"));
        WorkflowContext context = WorkflowContext.builder()
                .originalPrompt("做一个页面")
                .build();

        WorkflowContext updated = run(new RouterNode(routingService), context);

        assertEquals(CodeGenTypeEnum.HTML, updated.getGenerationType());
        assertTrue(updated.getWarnings().stream().anyMatch(w -> w.contains("回退")), updated.getWarnings().toString());
    }

    @Test
    void fallsBackWhenRoutingReturnsNull() {
        AiCodeGenTypeRoutingService routingService = mock(AiCodeGenTypeRoutingService.class);
        when(routingService.routeCodeGenType(anyString())).thenReturn(null);
        WorkflowContext context = WorkflowContext.builder().originalPrompt("需求").build();

        WorkflowContext updated = run(new RouterNode(routingService), context);

        assertEquals(CodeGenTypeEnum.HTML, updated.getGenerationType());
        assertTrue(updated.getWarnings().stream().anyMatch(w -> w.contains("未返回有效类型")));
    }

    private static WorkflowContext run(RouterNode node, WorkflowContext context) {
        Map<String, Object> result = node.create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        return WorkflowState.get(new MessagesState<>(result));
    }
}
