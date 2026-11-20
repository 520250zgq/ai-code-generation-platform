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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 代码质检 AI Service 的结构化输出测试：不调用真实模型，验证 JSON 到质检结果的映射。
 */
@ExtendWith(MockitoExtension.class)
class CodeQualityCheckServiceTest {

    @Mock
    private ChatModel chatModel;

    private CodeQualityCheckService service;

    @BeforeEach
    void setUp() {
        when(chatModel.supportedCapabilities()).thenReturn(Set.of());
        service = AiServices.builder(CodeQualityCheckService.class)
                .chatModel(chatModel)
                .build();
    }

    @Test
    void parsesStructuredReviewFromModelJson() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {
                  "valid": false,
                  "errors": [
                    "src/main.js：导入的模块 ./App.vue 不存在",
                    "package.json：缺少 scripts.build"
                  ],
                  "suggestions": ["补充响应式样式"]
                }
                """));

        var review = service.reviewCode("## 待检查代码\n...");

        assertFalse(review.getValid());
        assertEquals(2, review.getErrors().size());
        assertTrue(review.getErrors().get(0).contains("App.vue"));
        assertEquals(1, review.getSuggestions().size());
    }

    @Test
    void parsesPassingReview() {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response("""
                {"valid":true,"errors":[],"suggestions":[]}
                """));

        var review = service.reviewCode("## 待检查代码\n...");

        assertTrue(review.getValid());
        assertTrue(review.getErrors().isEmpty());
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
