package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 路由 AI Service 的确定性测试：不访问真实模型，验证枚举结构化输出和提示词装配。
 */
@ExtendWith(MockitoExtension.class)
class AiCodeGenTypeRoutingServiceTest {

    @Mock
    private ChatModel routingChatModel;

    private AiCodeGenTypeRoutingService routingService;

    @BeforeEach
    void setUp() {
        when(routingChatModel.supportedCapabilities()).thenReturn(Set.of());
        routingService = AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(routingChatModel)
                .build();
    }

    @Test
    void sendsRoutingPromptAndEnumFormatInstructions() {
        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        when(routingChatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            captured.set(request);
            return response("MULTI_FILE");
        });

        assertEquals(CodeGenTypeEnum.MULTI_FILE,
                routingService.routeCodeGenType("创建一个包含产品展示和联系页面的企业网站"));

        ChatRequest request = captured.get();
        String allText = request.messages().stream()
                .map(ChatMessage::toString)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(allText.contains("专业的代码生成方案路由器"),
                "请求必须携带 codegen-routing-system-prompt.txt 的内容");
        assertTrue(allText.contains("You must answer strictly with one of these enums"),
                "返回枚举时框架必须追加枚举格式约束");
        assertTrue(allText.contains("VUE_PROJECT"), "格式约束必须列出全部可选枚举");
    }

    @ParameterizedTest
    @CsvSource({
            "HTML, HTML",
            "multi_file, MULTI_FILE",
            "{\"value\":\"VUE_PROJECT\"}, VUE_PROJECT"
    })
    void parsesPlainOrJsonEnumResponse(String modelOutput, CodeGenTypeEnum expected) {
        when(routingChatModel.chat(any(ChatRequest.class))).thenReturn(response(modelOutput));

        assertEquals(expected, routingService.routeCodeGenType("创建一个应用"));
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
