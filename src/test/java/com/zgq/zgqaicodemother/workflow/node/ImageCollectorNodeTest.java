package com.zgq.zgqaicodemother.workflow.node;

import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import com.zgq.zgqaicodemother.workflow.service.ImageCollectionService;
import com.zgq.zgqaicodemother.workflow.state.WorkflowContext;
import com.zgq.zgqaicodemother.workflow.state.WorkflowState;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图片收集节点测试。
 */
class ImageCollectorNodeTest {

    @Test
    void writesPlanAndImagesToState() {
        ImageCollectionService service = mock(ImageCollectionService.class);
        ImageCollectionPlan plan = ImageCollectionPlan.builder()
                .contentImageTasks(List.of(new ImageCollectionPlan.ContentImageTask("office")))
                .build();
        ImageResource image = ImageResource.builder()
                .category(ImageCategoryEnum.CONTENT)
                .description("办公")
                .url("https://cdn.example.com/office.jpg")
                .source("pexels")
                .build();
        when(service.collect(anyString())).thenReturn(
                new ImageCollectionService.ImageCollectionOutcome(plan, List.of(image), List.of("warning")));

        WorkflowContext context = WorkflowContext.builder().originalPrompt("需求").build();
        Map<String, Object> result = new ImageCollectorNode(service).create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        WorkflowContext updated = WorkflowState.get(new MessagesState<>(result));

        assertEquals(1, updated.imageCount());
        assertEquals(plan, updated.getImageCollectionPlan());
        assertTrue(updated.getWarnings().contains("warning"));
        assertTrue(updated.getCurrentStep().contains("1 张"));
    }

    @Test
    void serviceFailureDoesNotFailWorkflow() {
        ImageCollectionService service = mock(ImageCollectionService.class);
        when(service.collect(anyString())).thenThrow(new RuntimeException("boom"));

        WorkflowContext context = WorkflowContext.builder().originalPrompt("需求").build();
        Map<String, Object> result = new ImageCollectorNode(service).create()
                .apply(new MessagesState<>(WorkflowState.initialInput(context))).join();
        WorkflowContext updated = WorkflowState.get(new MessagesState<>(result));

        assertEquals(0, updated.imageCount());
        assertTrue(updated.getWarnings().stream().anyMatch(w -> w.contains("异常")));
        assertTrue(!updated.hasError(), "图片失败不应阻断工作流");
    }
}
