package com.zgq.zgqaicodemother.workflow.state;

import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import com.zgq.zgqaicodemother.workflow.model.ImageCollectionPlan;
import com.zgq.zgqaicodemother.workflow.model.ImageResource;
import com.zgq.zgqaicodemother.workflow.model.enums.ImageCategoryEnum;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作流上下文序列化器测试：确保复杂状态可以在图节点之间安全克隆。
 */
class WorkflowContextSerializerTest {

    @Test
    void roundTripsAllFields() throws Exception {
        WorkflowContext original = WorkflowContext.builder()
                .workflowId("wf-1")
                .appId(10L)
                .userId(20L)
                .originalPrompt("做一个网站")
                .currentStep("代码生成完成")
                .enhancedPrompt("增强后的提示词")
                .generationType(CodeGenTypeEnum.MULTI_FILE)
                .generatedCodeDir("D:/tmp/code_output/multi_file_10")
                .buildResultDir("D:/tmp/code_output/multi_file_10")
                .buildSuccess(true)
                .errorMessage("warn")
                .startedAt(LocalDateTime.now().minusSeconds(3))
                .finishedAt(LocalDateTime.now())
                .images(new ArrayList<>(List.of(ImageResource.builder()
                        .category(ImageCategoryEnum.CONTENT)
                        .description("办公场景")
                        .url("https://cdn.example.com/office.jpg")
                        .source("pexels")
                        .build())))
                .imageCollectionPlan(ImageCollectionPlan.builder()
                        .contentImageTasks(List.of(new ImageCollectionPlan.ContentImageTask("office")))
                        .diagramTasks(List.of(new ImageCollectionPlan.DiagramTask("flowchart LR\n A-->B", "架构")))
                        .build())
                .warnings(new ArrayList<>(List.of("warning-1")))
                .build();

        WorkflowContext restored = roundTrip(original);

        assertNotNull(restored);
        assertEquals("wf-1", restored.getWorkflowId());
        assertEquals(10L, restored.getAppId());
        assertEquals(20L, restored.getUserId());
        assertEquals("做一个网站", restored.getOriginalPrompt());
        assertEquals(CodeGenTypeEnum.MULTI_FILE, restored.getGenerationType());
        assertTrue(restored.isBuildSuccess());
        assertEquals(1, restored.getImages().size());
        assertEquals(ImageCategoryEnum.CONTENT, restored.getImages().get(0).getCategory());
        assertEquals("https://cdn.example.com/office.jpg", restored.getImages().get(0).getUrl());
        assertNotNull(restored.getImageCollectionPlan());
        assertEquals("office", restored.getImageCollectionPlan().getContentImageTasks().get(0).getQuery());
        assertEquals("flowchart LR\n A-->B",
                restored.getImageCollectionPlan().getDiagramTasks().get(0).getMermaidCode());
        assertEquals(List.of("warning-1"), restored.getWarnings());
        assertNotNull(restored.getStartedAt());
        assertNotNull(restored.getFinishedAt());
    }

    @Test
    void handlesNullOptionalFields() throws Exception {
        WorkflowContext original = WorkflowContext.builder()
                .workflowId("wf-2")
                .originalPrompt("需求")
                .build();

        WorkflowContext restored = roundTrip(original);

        assertEquals("wf-2", restored.getWorkflowId());
        assertFalse(restored.isBuildSuccess());
        assertEquals(0, restored.imageCount());
        assertTrue(restored.getWarnings().isEmpty());
        assertEquals(null, restored.getImageCollectionPlan());
        assertEquals(null, restored.getAppId());
    }

    private static WorkflowContext roundTrip(WorkflowContext context) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            new WorkflowContextSerializer().write(context, output);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return new WorkflowContextSerializer().read(input);
        }
    }
}
