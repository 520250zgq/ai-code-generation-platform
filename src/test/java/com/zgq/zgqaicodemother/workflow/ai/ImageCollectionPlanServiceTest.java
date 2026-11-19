package com.zgq.zgqaicodemother.workflow.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 图片收集规划 AI Service 的结构化输出测试：不调用真实模型，验证 JSON 到计划的映射。
 */
@ExtendWith(MockitoExtension.class)
class ImageCollectionPlanServiceTest {

    @Mock
    private ChatModel chatModel;

    private ImageCollectionPlanService service;

    @BeforeEach
    void setUp() {
        when(chatModel.supportedCapabilities()).thenReturn(Set.of());
        service = AiServices.builder(ImageCollectionPlanService.class)
                .chatModel(chatModel)
                .build();
    }

    @Test
    void parsesStructuredPlanFromModelJson() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {
                  "contentImageTasks": [{"query": "technology office"}],
                  "illustrationTasks": [{"query": "team collaboration"}],
                  "diagramTasks": [{"mermaidCode": "flowchart LR\\n A-->B", "description": "系统架构"}],
                  "logoTasks": [{"description": "云舟科技 Logo"}]
                }
                """));

        var plan = service.planImageCollection("做一个科技公司官网");

        assertEquals(1, plan.getContentImageTasks().size());
        assertEquals("technology office", plan.getContentImageTasks().get(0).getQuery());
        assertEquals(1, plan.getIllustrationTasks().size());
        assertEquals("flowchart LR\n A-->B", plan.getDiagramTasks().get(0).getMermaidCode());
        assertEquals("云舟科技 Logo", plan.getLogoTasks().get(0).getDescription());
        assertEquals(4, plan.totalTasks());
    }

    @Test
    void parsesEmptyPlan() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {"contentImageTasks":[],"illustrationTasks":[],"diagramTasks":[],"logoTasks":[]}
                """));

        assertTrue(service.planImageCollection("简单页面").isEmpty());
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(1, 1))
                        .build())
                .build();
    }
}
