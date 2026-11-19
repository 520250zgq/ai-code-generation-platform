package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词增强节点测试。
 */
class PromptEnhancerNodeTest {

    @Test
    void writesEnhancedPromptToState() {
        WorkflowContext context = WorkflowContext.builder()
                .workflowId("wf-1")
                .originalPrompt("做一个企业官网")
                .images(List.of(ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("办公场景")
                        .url("https://cdn.example.com/office.jpg")
                        .source("test")
                        .build()))
                .build();

        Map<String, Object> result = new PromptEnhancerNode().create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        WorkflowContext updated = WorkflowState.get(new MessagesState<>(result));

        assertTrue(updated.getEnhancedPrompt().contains("https://cdn.example.com/office.jpg"));
        assertEquals("提示词增强完成", updated.getCurrentStep());
    }
}
